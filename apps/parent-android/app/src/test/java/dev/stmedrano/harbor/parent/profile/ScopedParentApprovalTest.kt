package dev.stmedrano.harbor.parent.profile

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.security.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class ScopedParentApprovalTest {
    private class Harness {
        val target = ChildBinding("device", "family", "child")
        var owner: ParentIdentity? = ParentIdentity("parent", "session")
        var now = 1000L
        var member = "parent"; var role = "owner"; var deviceChild = "child"
        var challengeFails = false; var remoteFails = false
        var reads = 0; var revokes = 0; var localClears = 0
        var challengeEntered: CompletableDeferred<Unit>? = null
        var challengeReleased: CompletableDeferred<Unit>? = null
        val api = object : ParentApi {
            override suspend fun listFamilies(): List<FamilyV1> = error("unused")
            override suspend fun createFamily(name: String, key: String): CreatedFamily = error("unused")
            override suspend fun createChild(request: CreateChildRequest): ChildV1 = error("unused")
            override suspend fun createPairing(childId: String): PairingCode = error("unused")
            override suspend fun revokeDevice(familyId: String, deviceId: String) {
                assertEquals(target.familyId, familyId); assertEquals(target.deviceId, deviceId); revokes++
            }
            override suspend fun readFamily(familyId: String): FamilySnapshot {
                reads++; assertEquals(target.familyId, familyId)
                return FamilySnapshot(FamilyV1(1, "family", "", "UTC", "", ""),
                    FamilyMemberV1(1, "member", "family", member, role, "active", "", ""),
                    listOf(ChildV1(1, "child", "family", "", "", "")),
                    listOf(DevicePublicV1(1, "device", "family", deviceChild, "", "", "active", null, "", "")), now)
            }
        }
        val mfa = object : MfaGateway {
            override suspend fun enrollTotp(): TotpEnrollment = error("unused")
            override suspend fun listFactors() = listOf("factor")
            override suspend fun challenge(factorId: String, code: String) {
                challengeEntered?.complete(Unit); challengeReleased?.await()
                if (challengeFails) throw MfaRequired()
            }
        }
        val approval = ScopedParentApproval(api, mfa, { owner },
            { if (remoteFails) error("offline logout") }, { localClears++ }, { now })
    }
    @Test fun unchallengedOrStaleMfaCannotReadOrRevoke() = runTest {
        val h = Harness()
        assertFalse(h.approval.authorize(h.target)); assertEquals(0, h.reads)
        h.approval.verify("factor", "123456"); h.now += 900001
        assertFalse(h.approval.authorize(h.target)); assertEquals(0, h.reads); assertEquals(0, h.revokes)
    }
    @Test fun failedChallengeCannotGrantApproval() = runTest {
        val h = Harness(); h.challengeFails = true
        try { h.approval.verify("factor", "123456"); fail() } catch (_: MfaRequired) { }
        assertFalse(h.approval.authorize(h.target))
    }
    @Test fun currentMembershipAndExactChildDeviceAreRequired() = runTest {
        val h = Harness(); h.approval.verify("factor", "123456")
        h.member = "foreign-parent"; assertFalse(h.approval.authorize(h.target))
        h.member = "parent"; h.deviceChild = "foreign-child"; assertFalse(h.approval.authorize(h.target))
        h.deviceChild = "child"; h.role = "child"; assertFalse(h.approval.authorize(h.target))
        assertEquals(0, h.revokes)
    }
    @Test fun revokeRechecksAccessAfterApproval() = runTest {
        val h = Harness(); h.approval.verify("factor", "123456")
        assertTrue(h.approval.authorize(h.target)); h.member = "foreign-parent"
        try { h.approval.revoke(h.target); fail() } catch (_: IllegalStateException) { }
        assertEquals(0, h.revokes)
    }
    @Test fun newerSessionCannotReuseAnOlderChallenge() = runTest {
        val h = Harness(); h.approval.verify("factor", "123456")
        h.owner = ParentIdentity("parent", "new-session")
        assertFalse(h.approval.authorize(h.target)); assertEquals(0, h.reads)
    }
    @Test fun localErasureRunsEvenWhenRemoteLogoutFails() = runTest {
        val h = Harness(); h.approval.verify("factor", "123456"); h.remoteFails = true
        try { h.approval.clear(); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, h.localClears); assertFalse(h.approval.authorize(h.target))
    }
    @Test fun successfulRevokeUsesFreshlyRecheckedExactTarget() = runTest {
        val h = Harness(); h.approval.verify("factor", "123456")
        assertTrue(h.approval.authorize(h.target)); h.approval.revoke(h.target)
        assertEquals(2, h.reads); assertEquals(1, h.revokes)
    }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun clearingDuringChallengePreventsLateApproval() = runTest {
        val h = Harness(); h.challengeEntered = CompletableDeferred(); h.challengeReleased = CompletableDeferred()
        val challenge = async { runCatching { h.approval.verify("factor", "123456") } }
        h.challengeEntered!!.await()
        val clearing = async { h.approval.clear() }; runCurrent()
        h.challengeReleased!!.complete(Unit)
        assertTrue(challenge.await().isFailure); clearing.await()
        assertFalse(h.approval.authorize(h.target)); assertEquals(1, h.localClears)
    }
}
