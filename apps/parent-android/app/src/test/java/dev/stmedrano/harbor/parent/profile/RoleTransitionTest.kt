package dev.stmedrano.harbor.parent.profile

import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.security.MfaRequired
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoleTransitionTest {
    private val binding = ChildBinding("11111111-1111-4111-8111-111111111111",
        "22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333")
    private val child = ProfileLease(ProfileRole.CHILD, binding.deviceId, 1)
    private inner class Harness {
        var current: ProfileLease? = child
        var bound: ChildBinding? = binding
        var parentClean = true
        val events = mutableListOf<String>()
        val transition = RoleTransition({ current }, { bound },
            { expected, cleanup ->
                if (current != expected) false else {
                    events += "hide"
                    val result = cleanup()
                    if (result) { current = null; events += "setup" }
                    result
                }
            }, { events += "parent-cleanup"; parentClean },
            { expected -> assertEquals(bound, expected); events += "erase-child"; bound = null })
        inner class Approval : ParentApproval {
            var allowed = true
            var stale = false
            var offline = false
            var gate: CompletableDeferred<Boolean>? = null
            override suspend fun authorize(binding: ChildBinding): Boolean {
                assertEquals(this@RoleTransitionTest.binding, binding)
                events += "authorize"
                return gate?.await() ?: allowed
            }
            override suspend fun revoke(binding: ChildBinding) {
                assertEquals(this@RoleTransitionTest.binding, binding)
                events += "revoke"
                if (stale) throw MfaRequired()
                if (offline) throw IOException("unconfirmed remote cleanup")
            }
            override suspend fun clear() { events += "clear-approval" }
        }
        val approval = Approval()
    }
    @Test fun foreignFamilyDenied() = runTest {
        val h = Harness(); h.approval.allowed = false
        assertFalse(h.transition.childToSetup(h.approval))
        assertEquals(child, h.current); assertEquals(binding, h.bound)
        assertEquals(listOf("authorize", "clear-approval"), h.events)
    }
    @Test fun staleMfaNoSideEffects() = runTest {
        val h = Harness(); h.approval.stale = true
        assertFalse(h.transition.childToSetup(h.approval))
        assertFalse(h.events.contains("erase-child")); assertFalse(h.events.contains("setup"))
        assertEquals(binding, h.bound); assertEquals(child, h.current)
        assertEquals("clear-approval", h.events.last())
    }
    @Test fun cancelClearsTemporaryCredentials() = runTest {
        val h = Harness(); h.approval.gate = CompletableDeferred()
        val work = launch { h.transition.childToSetup(h.approval) }
        runCurrent(); assertEquals(listOf("authorize"), h.events)
        work.cancel(); work.join()
        assertEquals(listOf("authorize", "clear-approval"), h.events)
        assertEquals(binding, h.bound); assertEquals(child, h.current)
    }
    @Test fun offlineRevokeKeepsChild() = runTest {
        val h = Harness(); h.approval.offline = true
        assertFalse(h.transition.childToSetup(h.approval))
        assertEquals(binding, h.bound); assertEquals(child, h.current)
        assertFalse(h.events.contains("erase-child")); assertFalse(h.events.contains("setup"))
    }
    @Test fun serverSuccessPrecedesKeyDeletionAndApprovalErasurePrecedesSetup() = runTest {
        val h = Harness()
        assertTrue(h.transition.childToSetup(h.approval))
        assertEquals(listOf("authorize", "hide", "revoke", "erase-child", "clear-approval", "setup"), h.events)
        assertNull(h.bound); assertNull(h.current)
    }
    @Test fun parentCleanupFailureBlocksChild() = runTest {
        val h = Harness(); h.current = ProfileLease(ProfileRole.PARENT, binding.familyId, 2); h.parentClean = false
        assertFalse(h.transition.parentToSetup())
        assertNotNull(h.current)
        assertEquals(listOf("hide", "parent-cleanup"), h.events)
        assertFalse(h.events.contains("setup"))
    }
    @Test fun callbackCannotOpenParentDashboard() = runTest {
        val h = Harness(); h.approval.allowed = false
        assertFalse(h.transition.childToSetup(h.approval))
        assertEquals(ProfileRole.CHILD, h.current?.role)
        assertFalse(h.events.contains("parent-cleanup"))
    }
    @Test fun lateApprovalCannotEraseNewBinding() = runTest {
        val h = Harness(); val gate = CompletableDeferred<Boolean>(); h.approval.gate = gate
        val work = launch { assertFalse(h.transition.childToSetup(h.approval)) }
        runCurrent()
        val fresh = binding.copy(deviceId = "44444444-4444-4444-8444-444444444444")
        h.bound = fresh; h.current = ProfileLease(ProfileRole.CHILD, fresh.deviceId, 2)
        gate.complete(true); work.join()
        assertEquals(fresh, h.bound)
        assertFalse(h.events.contains("revoke")); assertFalse(h.events.contains("erase-child"))
        assertEquals("clear-approval", h.events.last())
    }
}
