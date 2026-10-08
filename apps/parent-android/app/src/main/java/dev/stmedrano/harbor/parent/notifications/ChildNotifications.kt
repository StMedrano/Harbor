package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.child.*
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable data class ChildReceipt(val route: ParentRoute, val receivedAt: Long)

class ChildNotifications(private val current: () -> ProfileLease?, private val binding: () -> ChildBinding?,
    private val opted: () -> ChildBinding?, private val sync: suspend (ChildBinding) -> ChildSyncState,
    private val render: (ParentRoute) -> Unit, private val clear: () -> Unit, private val clock: () -> Long) {
    private val latest = MutableStateFlow<ChildReceipt?>(null)
    val receipt = latest.asStateFlow()
    private val lock = Any()
    private var generation = 0L
    private val seen = LinkedHashSet<ParentRoute>()
    private fun matches(lease: ProfileLease, expected: ChildBinding) = current() == lease &&
        lease.role == ProfileRole.CHILD && expected.deviceId == lease.ownerId && binding() == expected && opted() == expected
    suspend fun onMessage(data: Map<String, String>, lease: ProfileLease): Boolean {
        val expected = binding() ?: return false
        if (!matches(lease, expected) || data.keys != setOf("route")) return false
        val route = ParentMessageParser.parseRoute(data.getValue("route")) ?: return false
        if (route.deviceId != expected.deviceId || route.childId != expected.childId || route.familyId != expected.familyId) return false
        val ticket = synchronized(lock) { if (route in seen) return false; generation }
        val fresh = try { sync(expected) is ChildSyncState.Fresh }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
        if (!fresh) return false
        return synchronized(lock) {
            if (generation != ticket || !matches(lease, expected) || route in seen) return false
            val receivedAt = clock()
            if (receivedAt < 0) return false
            seen.add(route)
            if (seen.size > 100) seen.remove(seen.first())
            latest.value = ChildReceipt(route, receivedAt)
            render(route)
            true
        }
    }
    fun invalidate() = synchronized(lock) {
        generation++; seen.clear(); latest.value = null; clear()
    }
}
