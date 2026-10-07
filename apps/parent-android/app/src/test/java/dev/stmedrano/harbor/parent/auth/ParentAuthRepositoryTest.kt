package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Instant

class ParentAuthRepositoryTest {
    private val now = 1_000_000L
    private fun session(expired: Boolean = false) = UserSession("access", "refresh", expiresIn = 3600,
        tokenType = "bearer", expiresAt = Instant.fromEpochMilliseconds(if (expired) now - 1 else now + 3_600_000))
    private class Values : AuthValues {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun clear() { values.clear() }
    }
    // The real Android boundary is covered by KeystorePersistenceTest; this cipher
    // keeps these tests focused on the repository's network/lifecycle decisions.
    private fun store() = SecureAuthStore(Values(), object : AuthCipher {
        override fun encrypt(slot: String, value: ByteArray) = value
        override fun decrypt(slot: String, value: ByteArray) = value
    })
    private class Gateway(var stored: UserSession?) : AuthGateway {
        var refreshes = 0
        var changes = 0
        var recoveryFailure = false
        var rejectedRefresh = false
        var wrongIdentity = false
        var exchanges = 0
        override suspend fun signIn(email: String, password: String) = stored!!
        override suspend fun signUp(email: String, password: String) = "user"
        override suspend fun requestRecovery(email: String) { if (recoveryFailure) error("offline") }
        override suspend fun exchangeCode(code: String): UserSession { exchanges++; return stored!! }
        override suspend fun fetchVerifiedIdentity(session: UserSession, expectedEmail: String?, expectedUserId: String?): ParentIdentity {
            if (wrongIdentity) throw AuthSessionRejected()
            return ParentIdentity("user", "session")
        }
        override suspend fun changePassword(password: String) { changes++ }
        override suspend fun restoreStoredSession() = stored
        override suspend fun refresh(): UserSession {
            refreshes++; delay(10)
            if (rejectedRefresh) throw AuthSessionRejected()
            return stored!!.copy(expiresAt = Instant.fromEpochMilliseconds(4_600_000)).also { stored = it }
        }
        override suspend fun signOutCurrent() { stored = null }
        override suspend fun clearLocalSession() { stored = null }
    }
    private fun repo(gateway: Gateway, store: SecureAuthStore = store()) = ParentAuthRepository(gateway, store) { now }
    private suspend fun rejected(block: suspend () -> Unit) {
        var failure = false
        try { block() } catch (_: Exception) { failure = true }
        assertTrue("Operation must be refused", failure)
    }

    @Test fun ordinarySessionCannotChangePassword() = runTest {
        val gateway = Gateway(session())
        val repo = repo(gateway)
        repo.signIn("parent@example.invalid", "memory-only")
        assertFalse(repo.hasVerifiedRecovery())
        rejected { repo.changePassword("different-memory-only") }
        assertEquals(0, gateway.changes)
    }

    @Test fun restoreMissingSessionClearsVisibleIdentity() = runTest {
        val gateway = Gateway(session())
        val repo = repo(gateway)
        repo.signIn("parent@example.invalid", "memory-only")
        gateway.stored = null
        repo.restore()
        assertNull(repo.identity.value)
        rejected { repo.withAccessToken { it } }
    }

    @Test fun cancelPendingFlowAllowsConfirmedSignupFreshSignIn() = runTest {
        val gateway = Gateway(session())
        val store = store()
        val repo = repo(gateway, store)
        repo.beginSignup("parent@example.invalid", "memory-only")
        rejected { repo.signIn("parent@example.invalid", "memory-only") }
        repo.cancelEmailFlow()
        assertNull(store.transaction)
        repo.signIn("parent@example.invalid", "memory-only")
        assertEquals(ParentIdentity("user", "session"), repo.identity.value)
    }

    @Test fun successfulRecoveryPinsIdentityAcrossRecreationAndConsumesGate() = runTest {
        val gateway = Gateway(session())
        val store = store()
        repo(gateway, store).beginRecovery(" PARENT@example.invalid ")
        assertEquals("parent@example.invalid", store.transaction?.expectedEmail)
        val recreated = repo(gateway, store)
        recreated.consumeCallback("harbor-parent://auth/callback?code=one")
        assertTrue(recreated.hasVerifiedRecovery())
        assertEquals("user", store.transaction?.acceptedRecoverySubject)
        repo(gateway, store).changePassword("new-memory-only")
        assertNull(store.transaction)
        assertFalse(recreated.hasVerifiedRecovery())
        assertEquals(1, gateway.changes)
        rejected { recreated.changePassword("again") }
        rejected { recreated.consumeCallback("harbor-parent://auth/callback?code=one") }
        assertEquals(1, gateway.exchanges)
    }

    @Test fun failedRequestDoesNotCreateRecoveryGateAndSecondPendingFlowIsRefused() = runTest {
        val gateway = Gateway(session())
        val store = store()
        val repo = repo(gateway, store)
        gateway.recoveryFailure = true
        rejected { repo.beginRecovery("parent@example.invalid") }
        assertNull(store.transaction)
        gateway.recoveryFailure = false
        repo.beginRecovery("parent@example.invalid")
        rejected { repo.beginSignup("other@example.invalid", "memory-only") }
        assertEquals(AuthKind.RECOVERY, store.transaction?.kind)
    }

    @Test fun rejectedCallbackIdentityClearsSessionAndCannotUpdatePassword() = runTest {
        val gateway = Gateway(session())
        val store = store()
        val repo = repo(gateway, store)
        repo.beginRecovery("parent@example.invalid")
        gateway.wrongIdentity = true
        rejected { repo.consumeCallback("harbor-parent://auth/callback?code=one") }
        assertNull(repo.identity.value)
        assertNull(store.transaction)
        assertNull(gateway.stored)
        rejected { repo.changePassword("new") }
        assertEquals(0, gateway.changes)
    }

    @Test fun concurrentExpiredRequestsRefreshOnceAndInvalidRefreshSignsOut() = runTest {
        var clock = now
        val gateway = Gateway(session().copy(expiresAt = Instant.fromEpochMilliseconds(now + 1)))
        val repo = ParentAuthRepository(gateway, store()) { clock }
        repo.restore()
        assertEquals(0, gateway.refreshes)
        clock += 2
        val first = async { repo.withAccessToken { delay(10); it } }
        val second = async { repo.withAccessToken { it } }
        assertEquals("access", first.await())
        assertEquals("access", second.await())
        assertEquals(1, gateway.refreshes)
        val rejectedGateway = Gateway(session(expired = true)).apply { rejectedRefresh = true }
        val rejectedRepo = repo(rejectedGateway)
        rejectedRepo.restore()
        assertNull(rejectedRepo.identity.value)
        assertNull(rejectedGateway.stored)
        rejected { rejectedRepo.withAccessToken { it } }
    }
}
