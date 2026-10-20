package dev.stmedrano.harbor.parent.checkin

import dev.stmedrano.harbor.parent.child.UsageRequestRejected
import dev.stmedrano.harbor.parent.location.LocationFix
import dev.stmedrano.harbor.parent.location.locationInstantMs
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

fun interface CheckInTransport { suspend fun send(lease: ProfileLease, operation: String, body: String): String }

enum class CheckInProblem { COULD_NOT_LOAD, COULD_NOT_SEND, REQUEST_ENDED }

/** What the child sees. [loaded] is false until the first answer from the server, so the screen never claims "no requests" before it knows. */
data class CheckInUiState(
    val loaded: Boolean = false,
    val pending: CheckInPending? = null,
    val recent: List<CheckInRecent> = emptyList(),
    val sending: Boolean = false,
    val problem: CheckInProblem? = null,
)

/**
 * The child side of check-ins for one profile. It only ever answers a request a parent made: there is no call that starts
 * one, no free text and no SOS. A place is attached only when the phone already has a recent one; asking never turns
 * location on or starts new tracking.
 */
class ChildCheckInModel(
    private val transport: CheckInTransport,
    private val latestFix: () -> LocationFix?,
    private val current: (ProfileLease) -> Boolean,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(CheckInUiState())
    val state: StateFlow<CheckInUiState> = mutable.asStateFlow()

    private fun valid(lease: ProfileLease) = lease.role == ProfileRole.CHILD && current(lease)

    fun clear() { mutable.value = CheckInUiState() }

    suspend fun refresh(lease: ProfileLease) = lock.withLock { load(lease) }

    private suspend fun load(lease: ProfileLease) {
        if (!valid(lease)) return
        try {
            val reply = decodeCheckInState(transport.send(lease, "get-checkin-state", """{"version":1}"""))
            if (!valid(lease)) return
            mutable.value = mutable.value.copy(loaded = true, pending = reply.pending?.takeIf { locationInstantMs(it.expiresAt) > now() },
                recent = reply.recent, problem = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (valid(lease)) mutable.value = mutable.value.copy(problem = CheckInProblem.COULD_NOT_LOAD) }
    }

    /** Answers the open request with a preset. Returns whether the server confirmed it. */
    suspend fun reply(lease: ProfileLease, preset: CheckInPreset): Boolean = lock.withLock {
        val pending = mutable.value.pending ?: return@withLock false
        if (!valid(lease) || mutable.value.sending) return@withLock false
        mutable.value = mutable.value.copy(sending = true, problem = null)
        try {
            val body = Json.encodeToString(CheckInReplyV1.serializer(), validateCheckInReply(
                CheckInReplyV1(1, pending.requestId, preset, attachableFix()), now()))
            decodeCheckInReceipt(transport.send(lease, "reply-checkin", body))
            if (valid(lease)) { mutable.value = mutable.value.copy(sending = false, pending = null); load(lease) }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (rejected: UsageRequestRejected) {
            // The request closed or expired while the screen was open; show the real state instead of a vague error.
            if (valid(lease)) { mutable.value = mutable.value.copy(sending = false); load(lease)
                mutable.value = mutable.value.copy(problem = CheckInProblem.REQUEST_ENDED) }
            false
        } catch (_: Exception) {
            if (valid(lease)) mutable.value = mutable.value.copy(sending = false, problem = CheckInProblem.COULD_NOT_SEND)
            false
        }
    }

    /** The newest place the phone already knows, only if it is recent enough for the server to accept as "now". */
    private fun attachableFix(): CheckInFix? {
        val fix = latestFix() ?: return null
        val at = try { locationInstantMs(fix.at) } catch (_: Exception) { return null }
        if (at > now() + 300_000 || at < now() - CHECKIN_FIX_MAX_AGE_MS) return null
        return CheckInFix(fix.at, fix.latitude, fix.longitude, fix.accuracyM)
    }
}
