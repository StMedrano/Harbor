package dev.stmedrano.harbor.parent.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProfileCoordinator(
    private val store: ProfileStore,
    private val parentOwner: suspend () -> String?,
    private val childOwner: suspend () -> String?,
    private val stopRuntime: suspend () -> Unit,
    private val startRuntime: suspend (ProfileLease) -> Unit
) {
    private val mutableState = MutableStateFlow<ProfileState>(ProfileState.Transitioning)
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0L
    fun currentLease(): ProfileLease? = when (val value = state.value) {
        is ProfileState.Parent -> value.lease
        is ProfileState.Child -> value.lease
        else -> null
    }
    fun isCurrent(lease: ProfileLease) = currentLease() == lease
    suspend fun restore() = resolve(null)
    suspend fun activateParent() = resolve(ProfileRole.PARENT)
    suspend fun activateChild() = resolve(ProfileRole.CHILD)

    private suspend fun resolve(requested: ProfileRole?) {
        val captured = mutex.withLock {
            mutableState.value = ProfileState.Transitioning
            ++generation
        }
        try {
            mutex.withLock {
                if (captured != generation) return
                stopRuntime()
            }
            val hint = store.read()
            val parent = parentOwner()?.takeIf { it.isNotBlank() }
            val child = childOwner()?.takeIf { it.isNotBlank() }
            mutex.withLock {
                if (captured != generation) return
                if (parent != null && child != null) {
                    mutableState.value = ProfileState.Blocked(ProfileBlock.AMBIGUOUS)
                    return
                }
                val role = if (parent != null) ProfileRole.PARENT else if (child != null) ProfileRole.CHILD else null
                if ((requested != null && role != requested) || (hint != null && role != hint)) {
                    mutableState.value = ProfileState.Blocked(ProfileBlock.INVALID_CREDENTIALS)
                    return
                }
                if (role == null) { mutableState.value = ProfileState.Setup; return }
                val lease = ProfileLease(role, checkNotNull(parent ?: child), captured)
                store.write(role)
                startRuntime(lease)
                mutableState.value = if (role == ProfileRole.PARENT) ProfileState.Parent(lease) else ProfileState.Child(lease)
            }
        } catch (cancelled: CancellationException) {
            fail(captured, ProfileBlock.INVALID_CREDENTIALS)
            throw cancelled
        } catch (invalid: ProfileValidationFailure) {
            fail(captured, invalid.reason)
        } catch (_: Exception) {
            fail(captured, ProfileBlock.STORAGE_UNAVAILABLE)
        }
    }
    private suspend fun fail(captured: Long, reason: ProfileBlock) = withContext(NonCancellable) {
        mutex.withLock {
            if (captured != generation) return@withLock
            mutableState.value = ProfileState.Blocked(reason)
            try { stopRuntime() }
            catch (_: Exception) { /* Remain blocked; cleanup is never inferred. */ }
        }
    }
}
