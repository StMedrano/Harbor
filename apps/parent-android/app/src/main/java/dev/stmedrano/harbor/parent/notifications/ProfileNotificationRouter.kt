package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ProfileRegistrationState(val lease: ProfileLease? = null, val confirmed: Boolean = false, val busy: Boolean = false)

class ProfileNotificationRouter(private val current: () -> ProfileLease?, private val binding: () -> ChildBinding?,
    private val optedIn: (ProfileLease) -> Boolean, private val permission: () -> Boolean,
    private val enqueue: (ProfileLease, Map<String, String>) -> Boolean,
    private val parentSync: suspend (ProfileLease) -> Boolean,
    private val childToken: suspend () -> String,
    private val registerChild: suspend (ProfileLease, String) -> Boolean,
    private val hide: (ProfileLease) -> Unit) {
    private val mutableState = MutableStateFlow(ProfileRegistrationState())
    val state = mutableState.asStateFlow()
    private val operations = Mutex()
    private val lock = Any()
    private val running = mutableMapOf<ProfileLease, MutableSet<Job>>()
    private fun valid(lease: ProfileLease): Boolean {
        if (current() != lease || lease.generation <= 0 || !ParentMessageParser.uuid(lease.ownerId)) return false
        if (lease.role == ProfileRole.PARENT) return true
        val child = binding() ?: return false
        return child.deviceId == lease.ownerId &&
            listOf(child.deviceId, child.familyId, child.childId).all(ParentMessageParser::uuid)
    }
    fun accept(data: Map<String, String>, lease: ProfileLease): Boolean = runCatching {
        if (!valid(lease) || !optedIn(lease)) return false
        val payload = data.toMap()
        if (lease.role == ProfileRole.PARENT) {
            if (ParentMessageParser.parse(payload) == null) return false
        } else {
            if (payload.keys != setOf("route")) return false
            val route = ParentMessageParser.parseRoute(payload.getValue("route")) ?: return false
            val child = binding() ?: return false
            if (route.deviceId != child.deviceId || route.familyId != child.familyId || route.childId != child.childId) return false
        }
        valid(lease) && enqueue(lease, payload)
    }.getOrDefault(false)
    suspend fun syncToken(lease: ProfileLease) = coroutineScope {
        if (!valid(lease) || !optedIn(lease) || !permission()) return@coroutineScope
        val job = checkNotNull(currentCoroutineContext()[Job])
        synchronized(lock) { running.getOrPut(lease) { mutableSetOf() }.add(job) }
        try {
            operations.withLock {
                if (!valid(lease) || !optedIn(lease) || !permission()) return@withLock
                mutableState.value = ProfileRegistrationState(lease, busy = true)
                var confirmed = false
                try {
                    confirmed = if (lease.role == ProfileRole.PARENT) parentSync(lease) else {
                        val token = withTimeoutOrNull(10000) { childToken() }
                        token != null && token.isNotBlank() && valid(lease) && optedIn(lease) && permission() &&
                            registerChild(lease, token)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Backend registration remains unconfirmed; no error details are exposed. */ }
                finally {
                    synchronized(lock) {
                        if (mutableState.value.lease == lease) mutableState.value =
                            if (valid(lease)) ProfileRegistrationState(lease, confirmed = confirmed && optedIn(lease) && permission())
                            else ProfileRegistrationState()
                    }
                }
            }
        } finally {
            synchronized(lock) {
                running[lease]?.let { jobs -> jobs.remove(job); if (jobs.isEmpty()) running.remove(lease) }
            }
        }
    }
    fun stop(lease: ProfileLease) {
        val jobs = synchronized(lock) {
            if (mutableState.value.lease == lease) mutableState.value = ProfileRegistrationState()
            running[lease]?.toList().orEmpty()
        }
        jobs.forEach { it.cancel() }
        if (current() == null || current() == lease) hide(lease)
    }
}
