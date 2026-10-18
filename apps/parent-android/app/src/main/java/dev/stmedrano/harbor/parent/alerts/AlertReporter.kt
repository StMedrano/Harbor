package dev.stmedrano.harbor.parent.alerts

import dev.stmedrano.harbor.parent.child.UsageRequestRejected
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Sends one signed child operation and returns the response body. The adapter captures the validated child session. */
fun interface AlertTransport { suspend fun send(lease: ProfileLease, operation: String, body: String): String }

/**
 * Queues alerts and uploads them in order. At most one report is ever unconfirmed; a retry re-sends its exact bytes,
 * and the server deduplicates by alert id, so replay is idempotent. A rejection that proves our sequence is behind
 * (stale/conflict) is repaired from the verified checkpoint without losing the alerts in the rejected report. No part
 * of a message is present here — alerts are already text-free.
 */
class AlertReporter(
    private val store: AlertStore,
    private val current: (ProfileLease) -> Boolean,
    private val transport: AlertTransport,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private fun requireChild(lease: ProfileLease) { check(lease.role == ProfileRole.CHILD && lease.generation > 0 && current(lease)) { "Child profile changed" } }
    private fun sha256(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Remembers an alert, deduped by id. A lost local store drops it; the next flush restores the checkpoint from the server. */
    suspend fun record(lease: ProfileLease, alert: MessageAlert) = lock.withLock {
        requireChild(lease)
        validateMessageAlert(alert)
        try {
            store.update(lease.ownerId) { s ->
                val merged = (s.queue.filter { it.id.lowercase() != alert.id.lowercase() } + alert).sortedBy { alertInstantMs(it.at) }
                s.copy(queue = merged.takeLast(MAX_QUEUED_ALERTS))
            }
        } catch (_: AlertCheckpointLost) { /* Recovered by flush(). */ }
        Unit
    }

    /** Uploads everything queued, plus the current access flags when the server has not confirmed them. Network errors propagate; state stays queued. */
    suspend fun flush(lease: ProfileLease, access: AlertAccess) = lock.withLock {
        var repaired = false
        var batches = 0
        while (batches < MAX_BATCHES) {
            currentCoroutineContext().ensureActive()
            requireChild(lease)
            val state = readOrRestore(lease)
            val pending = state.pending ?: createPending(lease, state, access) ?: return@withLock
            try {
                upload(lease, pending)
                batches++
            } catch (rejected: UsageRequestRejected) {
                if (repaired || rejected.code !in REPAIRABLE) throw rejected
                repair(lease)
                repaired = true
            }
        }
    }

    private suspend fun readOrRestore(lease: ProfileLease): AlertStoredState {
        val saved = try { store.read(lease.ownerId) } catch (_: AlertCheckpointLost) { restore(lease); store.read(lease.ownerId) }
        return saved ?: store.update(lease.ownerId) { it }
    }
    private suspend fun checkpoint(lease: ProfileLease): AlertCheckpointReplyV1 {
        currentCoroutineContext().ensureActive(); requireChild(lease)
        val reply = decodeAlertCheckpoint(transport.send(lease, "get-message-alert-checkpoint", "{\"version\":1}"))
        currentCoroutineContext().ensureActive(); requireChild(lease)
        return reply
    }
    private suspend fun restore(lease: ProfileLease) { store.restoreCheckpoint(lease.ownerId, checkpoint(lease)) }
    private suspend fun repair(lease: ProfileLease) {
        val reply = checkpoint(lease)
        store.update(lease.ownerId) { s ->
            val back = (s.pending?.report?.alerts.orEmpty() + s.queue).distinctBy { it.id.lowercase() }.sortedBy { alertInstantMs(it.at) }
            s.copy(epochId = reply.epochId ?: s.epochId, nextSequence = maxOf(s.nextSequence, reply.sequence + 1),
                queue = back.takeLast(MAX_QUEUED_ALERTS), pending = null, reportedNotificationAccess = null, reportedSmsPermission = null)
        }
    }

    private fun createPending(lease: ProfileLease, state: AlertStoredState, access: AlertAccess): PendingAlert? {
        val nowMs = now()
        val observedAt = alertInstantString(nowMs)
        val observed = alertInstantMs(observedAt)
        val oldest = observed - ALERT_RETENTION_MS
        val usable = state.queue.filter { alertInstantMs(it.at) in oldest..observed }
        val due = usable.take(MAX_ALERTS_PER_REPORT)
        val accessUnchanged = access.notificationAccess == state.reportedNotificationAccess && access.smsPermission == state.reportedSmsPermission
        if (due.isEmpty() && accessUnchanged) {
            // Nothing to send; still drop alerts that fell out of the retention window so the queue cannot grow without bound.
            if (state.queue.any { alertInstantMs(it.at) < oldest }) store.update(lease.ownerId) { s -> s.copy(queue = s.queue.filter { alertInstantMs(it.at) >= oldest }) }
            return null
        }
        val report = validateAlertReport(
            AlertReportV1(1, state.epochId, state.nextSequence, observedAt, access.notificationAccess, access.smsPermission, due), nowMs,
        )
        val body = Json.encodeToString(AlertReportV1.serializer(), report)
        val pending = PendingAlert(report, body, sha256(body))
        val sentIds = due.map { it.id.lowercase() }.toSet()
        store.update(lease.ownerId) { s ->
            s.copy(nextSequence = report.sequence + 1, pending = pending,
                queue = s.queue.filter { alertInstantMs(it.at) >= oldest && it.id.lowercase() !in sentIds })
        }
        return pending
    }

    private suspend fun upload(lease: ProfileLease, pending: PendingAlert) {
        check(sha256(pending.bodyUtf8) == pending.hash && Json.decodeFromString<AlertReportV1>(pending.bodyUtf8) == pending.report) { "Pending alert bytes changed" }
        val reply = decodeAlertWriteReply(sendChecked(lease, "report-message-alert", pending.bodyUtf8), pending.report.sequence)
        requireChild(lease)
        store.update(lease.ownerId) { s ->
            if (s.pending == pending) s.copy(pending = null, lastConfirmedSequence = reply.sequence, receivedAt = reply.receivedAt,
                reportedNotificationAccess = pending.report.notificationAccess, reportedSmsPermission = pending.report.smsPermission) else s
        }
    }
    private suspend fun sendChecked(lease: ProfileLease, operation: String, body: String): String {
        currentCoroutineContext().ensureActive(); requireChild(lease)
        val response = transport.send(lease, operation, body)
        currentCoroutineContext().ensureActive(); requireChild(lease)
        return response
    }
    companion object {
        private const val MAX_BATCHES = 6
        private val REPAIRABLE = setOf("STALE_VERSION", "IDEMPOTENCY_CONFLICT")
    }
}
