package dev.stmedrano.harbor.parent.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
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
    suspend fun retryValidation(): Boolean {
        if (state.value != ProfileState.Blocked(ProfileBlock.NETWORK_UNAVAILABLE)) return false
        withTimeoutOrNull(15000) { restore() }
        return currentLease() != null
    }
    private suspend fun validatedOwner(lookup: suspend () -> String?): String? = try {
        lookup()?.takeIf { it.isNotBlank() }
    } catch (_: IOException) {
        throw ProfileValidationFailure(ProfileBlock.NETWORK_UNAVAILABLE)
    }
    suspend fun transitionToSetup(expected: ProfileLease, cleanup: suspend () -> Boolean): Boolean = mutex.withLock {
        if (!isCurrent(expected)) return@withLock false
        ++generation
        mutableState.value = ProfileState.Transitioning
        try {
            stopRuntime()
            if (!cleanup()) {
                restoreChildOrBlock(expected, ProfileBlock.INVALID_CREDENTIALS)
                return@withLock false
            }
            store.clear()
            mutableState.value = ProfileState.Setup
            true
        } catch (cancelled: CancellationException) {
            mutableState.value = ProfileState.Blocked(ProfileBlock.INVALID_CREDENTIALS)
            withContext(NonCancellable) {
                try { stopRuntime() } catch (_: Exception) { /* Remain blocked; no role escape. */ }
            }
            throw cancelled
        } catch (_: Exception) {
            restoreChildOrBlock(expected, ProfileBlock.STORAGE_UNAVAILABLE)
            false
        }
    }
    private suspend fun restoreChildOrBlock(expected: ProfileLease, reason: ProfileBlock) {
        if (expected.role == ProfileRole.CHILD) {
            try {
                if (store.read() == ProfileRole.CHILD && parentOwner() == null && childOwner() == expected.ownerId) {
                    val replacement = expected.copy(generation = generation)
                    startRuntime(replacement)
                    mutableState.value = ProfileState.Child(replacement)
                    return
                }
            } catch (cancelled: CancellationException) {
                mutableState.value = ProfileState.Blocked(reason)
                throw cancelled
            } catch (_: Exception) { /* Changed, revoked or unreadable credentials cannot resume. */ }
            try { stopRuntime() } catch (_: Exception) { /* No validated profile is exposed. */ }
        }
        mutableState.value = ProfileState.Blocked(reason)
    }

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
            val parent = validatedOwner(parentOwner)
            val child = validatedOwner(childOwner)
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
        } catch (timeout: TimeoutCancellationException) {
            fail(captured, ProfileBlock.NETWORK_UNAVAILABLE)
            throw timeout
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


