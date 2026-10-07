package dev.stmedrano.harbor.parent.security

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import dev.stmedrano.harbor.parent.family.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MfaRevocationTest {
    private val parent = ParentIdentity("parent", "session")
    private val date = "2026-01-01T00:00:00Z"
    private fun family(role: String = "owner", user: String = parent.userId) = FamilyState(FamilySnapshot(
        FamilyV1(1, "family", "Family", "UTC", date, date),
        FamilyMemberV1(1, "member", "family", user, role, "active", date, date),
        listOf(ChildV1(1, "child", "family", "Child", date, date)),
        listOf(DevicePublicV1(1, "device", "family", "child", "Phone", "standard", "active", null, date, date)), 1))
    private class Mfa : MfaGateway {
        var challenges = 0
        var release: CompletableDeferred<Unit>? = null
        override suspend fun enrollTotp() = TotpEnrollment("factor", "synthetic-secret", "otpauth://totp/synthetic")
        override suspend fun listFactors() = listOf("factor")
        override suspend fun challenge(factorId: String, code: String) { release?.await(); challenges++ }
    }

    @Test fun successfulChallengeNeverAutomaticallyRevokesAndRetryIsDeliberate() = runTest {
        val mfa = Mfa()
        var calls = 0; var refreshed = 0; var steppedUp = false
        val model = SecurityViewModel(mfa, { parent }, { family() }, { family, device ->
            assertEquals("family", family); assertEquals("device", device)
            calls++; if (!steppedUp) throw MfaRequired()
        }, { refreshed++ })
        model.requestRevocation("family", "device")
        assertEquals(SecurityPhase.CONFIRMATION, model.state.value.phase)
        model.confirmRetry()
        assertEquals(SecurityPhase.STEP_UP, model.state.value.phase)
        model.challenge("factor", "123456")
        steppedUp = true
        assertEquals(SecurityPhase.READY_TO_RETRY, model.state.value.phase)
        assertEquals(listOf("factor"), model.state.value.factors)
        assertEquals(1, calls)
        model.confirmRetry()
        assertEquals(2, calls); assertEquals(1, refreshed)
        assertEquals(SecurityPhase.ACCEPTED, model.state.value.phase)
        model.confirmRetry()
        assertEquals(2, calls)
    }

    @Test fun cachedForeignMissingDeviceAndNetworkFailureNeverShowCompletion() = runTest {
        for (state in listOf(family().copy(cached = true), family(role = "child"), family(user = "other"), family().copy(failure = FamilyFailure.NETWORK))) {
            var calls = 0
            val model = SecurityViewModel(Mfa(), { parent }, { state }, { _, _ -> calls++ }, {})
            model.requestRevocation("family", "device"); model.confirmRetry()
            assertEquals(0, calls); assertEquals(SecurityPhase.DENIED, model.state.value.phase)
        }
        var calls = 0
        val model = SecurityViewModel(Mfa(), { parent }, { family() }, { _, _ -> calls++; error("offline") }, {})
        model.requestRevocation("other-family", "device"); model.confirmRetry()
        model.requestRevocation("family", "missing"); model.confirmRetry()
        assertEquals(0, calls)
        model.requestRevocation("family", "device"); model.confirmRetry()
        assertEquals(1, calls); assertEquals(SecurityPhase.DENIED, model.state.value.phase)
    }

    @Test fun staleChallengeCannotAuthorizeRetryAndSetupSecretIsClearedAndRedacted() = runTest {
        var clock = 1000L
        var calls = 0
        val model = SecurityViewModel(Mfa(), { parent }, { family() }, { _, _ -> calls++; throw MfaRequired() }, {}, { clock })
        model.enrollTotp()
        assertEquals("synthetic-secret", model.state.value.enrollment?.secret)
        assertFalse(model.state.value.toString().contains("synthetic-secret"))
        model.requestRevocation("family", "device"); model.confirmRetry()
        model.challenge("factor", "123456")
        assertNull(model.state.value.enrollment)
        clock += 900001
        model.confirmRetry()
        assertEquals(1, calls); assertEquals(SecurityPhase.STEP_UP, model.state.value.phase)
        model.clear()
        assertNull(model.state.value.target)
    }

    @Test fun oldAccountChallengeCannotRestoreReadyStateAfterClear() = runTest {
        var actor: ParentIdentity? = parent
        val release = CompletableDeferred<Unit>()
        val mfa = Mfa().apply { this.release = release }
        val model = SecurityViewModel(mfa, { actor }, { family() }, { _, _ -> error("Must not revoke") }, {})
        model.requestRevocation("family", "device")
        val challenge = async { model.challenge("factor", "123456") }
        runCurrent()
        model.clear(); actor = parent.copy(sessionId = "new-session")
        release.complete(Unit); challenge.await()
        assertEquals(SecurityPhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.enrollment)
    }
}
