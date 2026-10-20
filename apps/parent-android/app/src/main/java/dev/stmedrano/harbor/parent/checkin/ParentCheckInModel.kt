package dev.stmedrano.harbor.parent.checkin

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

interface ParentCheckInApi {
    suspend fun read(deviceId: String): CheckInReadV1
    suspend fun request(deviceId: String): CheckInRequestReplyV1
}

enum class CheckInViewStatus { READY, ACCESS_LOST, LOAD_FAILED }

/** What a parent sees for one device. Entries are newest first and come only from a server reply. */
data class CheckInViewState(
    val status: CheckInViewStatus,
    val entries: List<CheckInEntry> = emptyList(),
    val asking: Boolean = false,
    /** The last "ask" did not go through. */
    val askFailed: Boolean = false,
    /** This is the previous reply shown again because a refresh failed. */
    val offline: Boolean = false,
) {
    /** A request still waiting for the child, judged against [nowMs] so one that lapsed is not shown as open. */
    fun open(nowMs: Long): CheckInEntry? = entries.firstOrNull {
        it.status == CheckInStatus.PENDING && dev.stmedrano.harbor.parent.location.locationInstantMs(it.expiresAt) > nowMs
    }
    val latestAnswer: CheckInEntry? get() = entries.firstOrNull { it.status == CheckInStatus.ANSWERED }
}

/**
 * Memory-only parent check-ins. Every reply is fenced by the captured identity, device and request order, and access loss
 * replaces a previous view. A parent can only ask; the answer is a preset from the child and an optional recent place.
 */
class ParentCheckInModel(private val api: ParentCheckInApi, private val currentIdentity: () -> ParentIdentity?) {
    data class Scope(val identity: ParentIdentity, val deviceId: String)
    data class Scoped(val scope: Scope, val view: CheckInViewState)
    private val mutable = MutableStateFlow<Scoped?>(null)
    val state: StateFlow<Scoped?> = mutable.asStateFlow()
    private val generation = AtomicLong()
    private var last: Pair<Scope, CheckInReadV1>? = null

    suspend fun load(identity: ParentIdentity, deviceId: String) {
        val scope = Scope(identity, deviceId)
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live()) return
        val previous = last?.takeIf { it.first == scope }?.second
        val carried = mutable.value?.takeIf { it.scope == scope }?.view
        try {
            val reply = api.read(deviceId)
            if (!live()) return
            last = scope to reply
            mutable.value = Scoped(scope, CheckInViewState(CheckInViewStatus.READY, reply.checkins, askFailed = carried?.askFailed == true))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FamilyAccessDenied) {
            if (!live()) return
            last = null
            mutable.value = Scoped(scope, CheckInViewState(CheckInViewStatus.ACCESS_LOST))
        } catch (_: Exception) {
            if (!live()) return
            mutable.value = Scoped(scope, if (previous != null) CheckInViewState(CheckInViewStatus.READY, previous.checkins, offline = true,
                askFailed = carried?.askFailed == true) else CheckInViewState(CheckInViewStatus.LOAD_FAILED))
        }
    }

    /** Asks this device's child to check in. If one is already open the server returns it instead of making a second. */
    suspend fun ask(identity: ParentIdentity, deviceId: String) {
        val scope = Scope(identity, deviceId)
        val before = mutable.value?.takeIf { it.scope == scope }?.view ?: return
        if (before.asking || before.status != CheckInViewStatus.READY) return
        mutable.value = Scoped(scope, before.copy(asking = true, askFailed = false))
        try {
            api.request(deviceId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FamilyAccessDenied) {
            if (currentIdentity() == identity) mutable.value = Scoped(scope, CheckInViewState(CheckInViewStatus.ACCESS_LOST))
            return
        } catch (_: Exception) {
            if (currentIdentity() == identity) mutable.value = Scoped(scope, before.copy(asking = false, askFailed = true))
            return
        }
        if (currentIdentity() != identity) return
        mutable.value = mutable.value?.takeIf { it.scope == scope }?.let { it.copy(view = it.view.copy(asking = false, askFailed = false)) }
        load(identity, deviceId)
    }

    /** Sign-out, membership loss or role change: drop everything and invalidate in-flight reads. */
    fun clear() { generation.incrementAndGet(); last = null; mutable.value = null }
}
