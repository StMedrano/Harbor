package dev.stmedrano.harbor.parent.activity

import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import dev.stmedrano.harbor.parent.alerts.alertInstantString
import dev.stmedrano.harbor.parent.child.UsageRequestRejected
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Sends one signed child operation and returns the response body. The adapter captures the validated child session. */
fun interface ActivityTransport { suspend fun send(lease: ProfileLease, operation: String, body: String): String }

/** What the phone reports alongside its events: whether controls are being enforced and which policy version. */
data class ActivityStatusNow(val controlsOn: Boolean, val policyVersion: Long, val screenTime: ScreenTime? = null)

/**
 * Queues activity events and uploads them in order, the same way alerts are uploaded: at most one report is ever
 * unconfirmed, a retry re-sends its exact bytes, the server deduplicates by event id, and a stale or conflicting
 * sequence is repaired from the verified checkpoint without losing queued events.
 */
class ActivityReporter(
    private val store: ActivityStore,
    private val current: (ProfileLease) -> Boolean,
    private val transport: ActivityTransport,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private fun requireChild(lease: ProfileLease) { check(lease.role == ProfileRole.CHILD && lease.generation > 0 && current(lease)) { "Child profile changed" } }
    private fun sha256(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Adds events (deduped by id) and applies [change] to the remembered state in the same write. */
    suspend fun record(lease: ProfileLease, events: List<ActivityEvent>, change: (ActivityStoredState) -> ActivityStoredState = { it }) = lock.withLock {
        requireChild(lease)
        events.forEach(::validateActivityEvent)
        try {
            store.update(lease.ownerId) { s ->
                val fresh = events.distinctBy { it.id.lowercase() }
                val ids = fresh.map { it.id.lowercase() }.toSet()
                val merged = (s.queue.filter { it.id.lowercase() !in ids } + fresh).sortedBy { alertInstantMs(it.at) }
                change(s.copy(queue = merged.takeLast(MAX_QUEUED_ACTIVITY)))
            }
        } catch (_: ActivityCheckpointLost) { /* Recovered by flush(). */ }
        Unit
    }

    suspend fun state(lease: ProfileLease): ActivityStoredState? = lock.withLock {
        requireChild(lease)
        try { store.read(lease.ownerId) } catch (_: ActivityCheckpointLost) { null }
    }

    /** Uploads everything queued, plus the status when the server has not confirmed it. Network errors propagate. */
    suspend fun flush(lease: ProfileLease, status: ActivityStatusNow) = lock.withLock {
        var repaired = false
        var batches = 0
        while (batches < MAX_BATCHES) {
            currentCoroutineContext().ensureActive()
            requireChild(lease)
            val state = readOrRestore(lease)
            val pending = state.pending ?: createPending(lease, state, status) ?: return@withLock
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

    private suspend fun readOrRestore(lease: ProfileLease): ActivityStoredState {
        val saved = try { store.read(lease.ownerId) } catch (_: ActivityCheckpointLost) { restore(lease); store.read(lease.ownerId) }
        return saved ?: store.update(lease.ownerId) { it }
    }
    private suspend fun checkpoint(lease: ProfileLease): ActivityCheckpointReplyV1 {
        currentCoroutineContext().ensureActive(); requireChild(lease)
        val reply = decodeActivityCheckpoint(transport.send(lease, "get-device-activity-checkpoint", "{\"version\":1}"))
        currentCoroutineContext().ensureActive(); requireChild(lease)
        return reply
    }
    private suspend fun restore(lease: ProfileLease) { store.restoreCheckpoint(lease.ownerId, checkpoint(lease)) }
    private suspend fun repair(lease: ProfileLease) {
        val reply = checkpoint(lease)
        store.update(lease.ownerId) { s ->
            val back = (s.pending?.report?.events.orEmpty() + s.queue).distinctBy { it.id.lowercase() }.sortedBy { alertInstantMs(it.at) }
            s.copy(epochId = reply.epochId ?: s.epochId, nextSequence = maxOf(s.nextSequence, reply.sequence + 1),
                queue = back.takeLast(MAX_QUEUED_ACTIVITY), pending = null, reportedControlsOn = null, reportedPolicyVersion = null)
        }
    }

    private fun createPending(lease: ProfileLease, state: ActivityStoredState, status: ActivityStatusNow): PendingActivity? {
        val nowMs = now()
        val observedAt = alertInstantString(nowMs)
        val observed = alertInstantMs(observedAt)
        val oldest = observed - ACTIVITY_RETENTION_MS
        val usable = state.queue.filter { alertInstantMs(it.at) in oldest..observed }
        val due = usable.take(ACTIVITY_MAX_PER_REPORT)
        val statusUnchanged = status.controlsOn == state.reportedControlsOn && status.policyVersion == state.reportedPolicyVersion &&
            status.screenTime == state.reportedScreenTime
        if (due.isEmpty() && statusUnchanged) {
            if (state.queue.any { alertInstantMs(it.at) < oldest }) store.update(lease.ownerId) { s -> s.copy(queue = s.queue.filter { alertInstantMs(it.at) >= oldest }) }
            return null
        }
        val report = validateActivityReport(ActivityReportV1(1, state.epochId, state.nextSequence, observedAt,
            ActivityStatus(status.controlsOn, status.policyVersion, status.screenTime), due), nowMs)
        val body = encodeActivityReport(report)
        val pending = PendingActivity(report, body, sha256(body))
        val sentIds = due.map { it.id.lowercase() }.toSet()
        store.update(lease.ownerId) { s ->
            s.copy(nextSequence = report.sequence + 1, pending = pending,
                queue = s.queue.filter { alertInstantMs(it.at) >= oldest && it.id.lowercase() !in sentIds })
        }
        return pending
    }

    private suspend fun upload(lease: ProfileLease, pending: PendingActivity) {
        check(sha256(pending.bodyUtf8) == pending.hash && Json.decodeFromString<ActivityReportV1>(pending.bodyUtf8) == pending.report) { "Pending activity bytes changed" }
        currentCoroutineContext().ensureActive(); requireChild(lease)
        val reply = decodeActivityWriteReply(transport.send(lease, "report-device-activity", pending.bodyUtf8), pending.report.sequence)
        currentCoroutineContext().ensureActive(); requireChild(lease)
        store.update(lease.ownerId) { s ->
            if (s.pending == pending) s.copy(pending = null, lastConfirmedSequence = reply.sequence, receivedAt = reply.receivedAt,
                reportedControlsOn = pending.report.status.controlsOn, reportedPolicyVersion = pending.report.status.policyVersion,
                reportedScreenTime = pending.report.status.screenTime) else s
        }
    }
    companion object {
        private const val MAX_BATCHES = 6
        private val REPAIRABLE = setOf("STALE_VERSION", "IDEMPOTENCY_CONFLICT")
    }
}
