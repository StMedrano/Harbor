package dev.stmedrano.harbor.parent

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class ParentRuntimeState(val signingOut: Boolean = false, val cleanupConfirmed: Boolean = false)

// The application wires these callbacks to its existing Auth, notifications,
// private channels and cache. No credentials are stored for a later cleanup run.
class ParentRuntime(private val currentIdentity: () -> ParentIdentity?,
    private val captureToken: suspend (ParentIdentity) -> String,
    private val invalidateRegistration: () -> Unit, private val disconnect: () -> Unit,
    private val hideViews: () -> Unit, private val removeRegistration: suspend (ParentIdentity, String) -> Unit,
    private val deleteToken: suspend () -> Unit, private val signOutAuth: suspend (ParentIdentity) -> Unit,
    private val clearLocal: suspend (ParentIdentity?) -> Unit) {
    private val mutableState = MutableStateFlow(ParentRuntimeState())
    val state = mutableState.asStateFlow()
    private val logout = Mutex()
    private val authLock = Any()
    private val authJobs = mutableSetOf<Job>()
    private var authGeneration = 0L

    suspend fun authAction(action: suspend () -> Unit) {
        val job = checkNotNull(currentCoroutineContext()[Job])
        val ticket = synchronized(authLock) {
            check(!state.value.signingOut) { "Finish signing out before starting Auth work" }
            authJobs.add(job); authGeneration
        }
        try {
            action()
            synchronized(authLock) { if (ticket != authGeneration) throw CancellationException("Auth action superseded") }
        } finally { synchronized(authLock) { authJobs.remove(job) } }
    }

    suspend fun signOutCurrent() {
        val owner = currentIdentity()
        logout.withLock {
            // A queued old-screen action must not sign out a later account.
            if (currentIdentity() != owner) return@withLock
            val caller = currentCoroutineContext()[Job]
            val pending = synchronized(authLock) {
                mutableState.value = ParentRuntimeState(signingOut = true)
                authGeneration++
                authJobs.filter { it != caller }
            }
            pending.forEach { it.cancel(CancellationException("Current account is signing out")) }
            invalidateRegistration(); disconnect(); hideViews()
            var removed = false; var deleted = false; var authStopped = false
            fun stillOwner() = currentIdentity() == owner || currentIdentity() == null
            try {
                val token = if (owner != null) try {
                    withTimeoutOrNull(2000) { captureToken(owner) }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null } else null
                if (owner != null && token != null && stillOwner()) removed = attempt { removeRegistration(owner, token) }
                if (stillOwner()) deleted = attempt { deleteToken() }
                if (owner != null && stillOwner()) authStopped = attempt { signOutAuth(owner) }
            } finally {
                // Failure and cancellation still erase this account locally. A
                // newer verified account owns its own cleanup and is preserved.
                withContext(NonCancellable) {
                    if (stillOwner()) clearLocal(owner)
                }
                mutableState.value = ParentRuntimeState(cleanupConfirmed = removed && deleted && authStopped && stillOwner())
            }
        }
    }
    private suspend fun attempt(action: suspend () -> Unit): Boolean = withTimeoutOrNull(5000) {
        try { action(); true }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    } == true
}
