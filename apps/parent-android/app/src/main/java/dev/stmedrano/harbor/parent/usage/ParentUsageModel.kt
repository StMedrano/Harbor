package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

interface ParentUsageApi { suspend fun read(deviceId: String): UsageReadReplyV1 }

/** A usage view is valid only for the parent session and device it was read for. */
data class ParentUsageScope(val identity: ParentIdentity, val deviceId: String)
data class ScopedUsageView(val scope: ParentUsageScope, val view: UsageViewState)

/**
 * Memory-only, read-only parent usage. Every reply is fenced by the captured identity, device and request order;
 * access loss replaces (never keeps) a previous report, and nothing is persisted.
 */
class ParentUsageModel(private val api: ParentUsageApi, private val currentIdentity: () -> ParentIdentity?,
    private val now: () -> Long = System::currentTimeMillis) {
    private val mutable = MutableStateFlow<ScopedUsageView?>(null)
    val state: StateFlow<ScopedUsageView?> = mutable.asStateFlow()
    private val generation = AtomicLong()
    private var lastReply: Pair<ParentUsageScope, UsageReadReplyV1>? = null

    suspend fun load(identity: ParentIdentity, deviceId: String) {
        val scope = ParentUsageScope(identity, deviceId)
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live()) return
        val previous = lastReply?.takeIf { it.first == scope }?.second
        try {
            val reply = api.read(deviceId)
            if (!live()) return
            lastReply = scope to reply
            mutable.value = ScopedUsageView(scope, UsageViewState.fromReply(reply, now()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FamilyAccessDenied) {
            if (!live()) return
            lastReply = null
            mutable.value = ScopedUsageView(scope, UsageViewState(UsageViewStatus.ACCESS_LOST, UsageViewOrigin.PARENT_READ))
        } catch (_: Exception) {
            if (!live()) return
            mutable.value = ScopedUsageView(scope, if (previous != null) UsageViewState.fromReply(previous, now(), offline = true)
                else UsageViewState(UsageViewStatus.LOAD_FAILED, UsageViewOrigin.PARENT_READ))
        }
    }

    /** Sign-out, membership loss or role change: drop everything and invalidate in-flight reads. */
    fun clear() { generation.incrementAndGet(); lastReply = null; mutable.value = null }
}
