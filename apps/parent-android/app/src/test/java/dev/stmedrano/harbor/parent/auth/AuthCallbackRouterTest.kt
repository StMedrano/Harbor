package dev.stmedrano.harbor.parent.auth

import org.junit.Assert.*
import org.junit.Test

class AuthCallbackRouterTest {
    private val now = 1_000_000L
    private val pending = AuthTransaction(AuthKind.RECOVERY, "parent@example.invalid", null, now - 1000)
    private val router = AuthCallbackRouter { now }

    @Test fun exactCodeUsesStoredFlow() {
        val result = router.parse("harbor-parent://auth/callback?code=one-time-code", pending)
        assertEquals("one-time-code", result?.code)
        assertEquals(pending, result?.transaction)
    }

    @Test fun rejectsUnsolicitedConsumedExpiredAndFutureTransactions() {
        val uri = "harbor-parent://auth/callback?code=code"
        assertNull(router.parse(uri, null))
        assertNull(router.parse(uri, pending.copy(acceptedRecoverySubject = "already-used")))
        assertNull(router.parse(uri, pending.copy(startedAtMillis = now - 900_001)))
        assertNull(router.parse(uri, pending.copy(startedAtMillis = now + 1)))
    }

    @Test fun ordinaryTokenLabelCannotUnlockRecovery() {
        assertNull(router.parse("harbor-parent://auth/callback?type=recovery#access_token=test_only", null))
        listOf(
            "https://auth/callback?code=code", "harbor-parent://other/callback?code=code",
            "harbor-parent://auth/callback/extra?code=code", "harbor-parent://auth:9/callback?code=code",
            "harbor-parent://user@auth/callback?code=code", "harbor-parent://auth/callback?code=code#access_token=secret",
            "harbor-parent://auth/callback?code=code&type=recovery", "harbor-parent://auth/callback?code=first&code=second",
            "harbor-parent://auth/callback?code=", "harbor-parent://auth/callback?code=%0Asecret"
        ).forEach { assertNull(it, router.parse(it, pending)) }
    }
}
