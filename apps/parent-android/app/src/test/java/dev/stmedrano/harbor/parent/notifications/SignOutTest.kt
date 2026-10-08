package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.ParentRuntime
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignOutTest {
    private val parent = ParentIdentity("parent-a", "session-a")

    @Test fun RenderingAndMarkerClearBeforeBlockedRemoteCleanup() = runTest {
        val remote = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        var identity: ParentIdentity? = parent
        val runtime = ParentRuntime(
            currentIdentity = { identity }, captureToken = { assertEquals(parent, it); "captured-owner-token" },
            invalidateRegistration = { events += "marker cleared" }, disconnect = { events += "disconnected" },
            hideViews = { events += "views hidden" }, removeRegistration = { owner, token ->
                assertEquals(parent, owner); assertEquals("captured-owner-token", token)
                events += "remove attempted"; remote.await()
            }, deleteToken = { events += "provider deleted" }, signOutAuth = { events += "auth logout" },
            clearLocal = { events += "local cleared"; identity = null })
        val request = async { runtime.signOutCurrent() }
        runCurrent()
        assertTrue(runtime.state.value.signingOut)
        assertEquals(listOf("marker cleared", "disconnected", "views hidden", "remove attempted"), events)
        remote.complete(Unit); request.await()
        assertFalse(runtime.state.value.signingOut)
        assertTrue(runtime.state.value.cleanupConfirmed)
        assertNull(identity)
        assertEquals(listOf("provider deleted", "auth logout", "local cleared"), events.takeLast(3))
    }

    @Test fun OfflineFailuresStillClearLocalAndDoNotClaimRemoteCleanup() = runTest {
        val attempts = mutableListOf<String>()
        var identity: ParentIdentity? = parent
        var secret: String? = "synthetic-in-memory-secret"
        val runtime = ParentRuntime(
            currentIdentity = { identity }, captureToken = { "captured-owner-token" },
            invalidateRegistration = { attempts += "marker cleared" }, disconnect = {}, hideViews = {},
            removeRegistration = { _, _ -> attempts += "remove"; error("offline") },
            deleteToken = { attempts += "delete"; error("offline") },
            signOutAuth = { attempts += "auth"; error("offline") },
            clearLocal = { identity = null; secret = null })
        runtime.signOutCurrent()
        assertNull(identity); assertNull(secret)
        assertFalse(runtime.state.value.signingOut)
        assertFalse(runtime.state.value.cleanupConfirmed)
        assertEquals(listOf("marker cleared", "remove", "delete", "auth"), attempts)
    }

    @Test fun StaleCleanupCompletionCannotClearNewAccount() = runTest {
        val remote = CompletableDeferred<Unit>()
        var identity: ParentIdentity? = parent
        var cleared = 0; var providerDeletes = 0; var authLogouts = 0
        val runtime = ParentRuntime(currentIdentity = { identity }, captureToken = { "captured-owner-token" },
            invalidateRegistration = {}, disconnect = {}, hideViews = {}, removeRegistration = { _, _ -> remote.await() },
            deleteToken = { providerDeletes++ }, signOutAuth = { authLogouts++ }, clearLocal = { cleared++ })
        val request = async { runtime.signOutCurrent() }; runCurrent()
        val newAccount = ParentIdentity("parent-b", "session-b")
        identity = newAccount
        remote.complete(Unit); request.await()
        assertEquals(newAccount, identity)
        assertEquals(0, cleared); assertEquals(0, providerDeletes); assertEquals(0, authLogouts)
        assertFalse(runtime.state.value.cleanupConfirmed)
    }

    @Test fun SignOutCancelsPendingAuthAndRejectsNewAuthUntilCleanupFinishes() = runTest {
        var identity: ParentIdentity? = parent
        var entered = 0
        val authResponse = CompletableDeferred<Unit>()
        val cleanupResponse = CompletableDeferred<Unit>()
        val runtime = ParentRuntime(currentIdentity = { identity }, captureToken = { "captured-owner-token" },
            invalidateRegistration = {}, disconnect = {}, hideViews = {},
            removeRegistration = { _, _ -> cleanupResponse.await() }, deleteToken = {}, signOutAuth = {}, clearLocal = { identity = null })
        val pending = async { runtime.authAction { authResponse.await() } }
        runCurrent()
        val logout = async { runtime.signOutCurrent() }
        runCurrent()
        assertTrue(pending.isCancelled)
        assertTrue(runtime.state.value.signingOut)
        assertTrue(runCatching { runtime.authAction { entered++ } }.isFailure)
        assertEquals(0, entered)
        cleanupResponse.complete(Unit); logout.await()
        runtime.authAction { entered++ }
        assertEquals(1, entered)
    }
}
