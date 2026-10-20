package dev.stmedrano.harbor.parent.location

import dev.stmedrano.harbor.parent.child.UsageRequestRejected
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/** Sends one signed child operation and returns the response body. The adapter captures the validated child session. */
fun interface LocationTransport { suspend fun send(lease: ProfileLease, operation: String, body: String): String }

/**
 * Queues fixes and uploads them in order. At most one report is ever unconfirmed; a retry re-sends its exact bytes.
 * A server rejection that proves our sequence is behind (stale/conflict) is repaired from the verified checkpoint
 * without losing the fixes that were in the rejected report.
 */
class LocationEngine(
    private val store: LocationStore,
    private val current: (ProfileLease) -> Boolean,
    private val transport: LocationTransport,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private fun requireChild(lease: ProfileLease) { check(lease.role == ProfileRole.CHILD && lease.generation > 0 && current(lease)) { "Child profile changed" } }
    private fun sha256(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    /** Remembers a fix. A lost local store drops the fix; the next flush restores the checkpoint from the server. */
    suspend fun record(lease: ProfileLease, fix: LocationFix) = lock.withLock {
        requireChild(lease)
        validateLocationFix(fix)
        try {
            store.update(lease.ownerId) { s ->
                val merged = (s.queue.filter { it.at != fix.at } + fix).sortedBy { locationInstantMs(it.at) }
                s.copy(queue = merged.takeLast(MAX_QUEUED_FIXES))
            }
        } catch (_: LocationCheckpointLost) { /* Recovered by flush(). */ }
        Unit
    }

    /** Uploads everything queued, plus the current status when the server has not confirmed it. Network errors propagate; state stays queued. */
    suspend fun flush(lease: ProfileLease, status: LocationStatus) = lock.withLock {
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

    private suspend fun readOrRestore(lease: ProfileLease): LocationStoredState {
        val saved = try { store.read(lease.ownerId) } catch (_: LocationCheckpointLost) { restore(lease); store.read(lease.ownerId) }
        return saved ?: store.update(lease.ownerId) { it }
    }
    private suspend fun checkpoint(lease: ProfileLease): LocationCheckpointReplyV1 {
        currentCoroutineContext().ensureActive(); requireChild(lease)
        val reply = decodeLocationCheckpoint(transport.send(lease, "get-device-location-checkpoint", "{\"version\":1}"))
        currentCoroutineContext().ensureActive(); requireChild(lease)
        return reply
    }
    private suspend fun restore(lease: ProfileLease) { store.restoreCheckpoint(lease.ownerId, checkpoint(lease)) }
    private suspend fun repair(lease: ProfileLease) {
        val reply = checkpoint(lease)
        store.update(lease.ownerId) { s ->
            val back = (s.pending?.report?.fixes.orEmpty() + s.queue).distinctBy { it.at }.sortedBy { locationInstantMs(it.at) }
            s.copy(epochId = reply.epochId ?: s.epochId, nextSequence = maxOf(s.nextSequence, reply.sequence + 1),
                queue = back.takeLast(MAX_QUEUED_FIXES), pending = null, reportedStatus = null)
        }
    }

    private fun createPending(lease: ProfileLease, state: LocationStoredState, status: LocationStatus): PendingLocation? {
        val nowMs = now()
        val observedAt = locationInstantString(nowMs)
        val observed = locationInstantMs(observedAt)
        val oldest = observed - LOCATION_RETENTION_MS
        val usable = state.queue.filter { locationInstantMs(it.at) in oldest..observed }
        val due = usable.take(MAX_LOCATION_FIXES)
        if (due.isEmpty() && status == state.reportedStatus) {
            // Nothing to send; still drop fixes that fell out of the retention window so the queue cannot grow without bound.
            if (state.queue.any { locationInstantMs(it.at) < oldest }) store.update(lease.ownerId) { s -> s.copy(queue = s.queue.filter { locationInstantMs(it.at) >= oldest }) }
            return null
        }
        val report = validateLocationReport(LocationReportV1(1, state.epochId, state.nextSequence, observedAt, status, due), nowMs)
        val body = Json.encodeToString(LocationReportV1.serializer(), report)
        val pending = PendingLocation(report, body, sha256(body))
        val sentAt = due.map { it.at }.toSet()
        store.update(lease.ownerId) { s ->
            s.copy(nextSequence = report.sequence + 1, pending = pending,
                queue = s.queue.filter { locationInstantMs(it.at) >= oldest && it.at !in sentAt })
        }
        return pending
    }

    private suspend fun upload(lease: ProfileLease, pending: PendingLocation) {
        check(sha256(pending.bodyUtf8) == pending.hash && Json.decodeFromString<LocationReportV1>(pending.bodyUtf8) == pending.report) { "Pending location bytes changed" }
        val reply = decodeLocationWriteReply(sendChecked(lease, "report-device-location", pending.bodyUtf8), pending.report.sequence)
        requireChild(lease)
        store.update(lease.ownerId) { s ->
            if (s.pending == pending) s.copy(pending = null, lastConfirmedSequence = reply.sequence, receivedAt = reply.receivedAt, reportedStatus = pending.report.status) else s
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
