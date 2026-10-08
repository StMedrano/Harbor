package dev.stmedrano.harbor.parent.profile

import dev.stmedrano.harbor.parent.child.ChildBinding
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RoleTransitionCompositionTest {
    private class Store : ProfileStore {
        var role: ProfileRole? = ProfileRole.CHILD
        override suspend fun read() = role
        override suspend fun write(role: ProfileRole) { this.role = role }
        override suspend fun clear() { role = null }
    }
    @Test fun offlineRevocationPreservesValidatedChildWithFencedGeneration() = runTest {
        val store = Store(); var starts = 0; var erased = false; var cleared = false
        val coordinator = ProfileCoordinator(store, { null }, { "device" }, {}, { starts++ })
        coordinator.restore(); val previous = checkNotNull(coordinator.currentLease())
        val target = ChildBinding("device", "family", "child")
        val transition = RoleTransition(coordinator::currentLease, { target }, coordinator::transitionToSetup,
            { false }, { erased = true })
        val approval = object : ParentApproval {
            override suspend fun authorize(binding: ChildBinding) = true
            override suspend fun revoke(binding: ChildBinding) { error("offline") }
            override suspend fun clear() { cleared = true }
        }
        assertFalse(transition.childToSetup(approval))
        assertTrue(cleared); assertFalse(erased)
        assertEquals(ProfileRole.CHILD, store.role)
        assertEquals(ProfileRole.CHILD, coordinator.currentLease()?.role)
        assertEquals("device", coordinator.currentLease()?.ownerId)
        assertFalse(coordinator.isCurrent(previous)); assertEquals(2, starts)
    }
    @Test fun failedLocalErasureCannotResumeAnAlreadyRemovedChild() = runTest {
        val store = Store(); var owner: String? = "device"; var starts = 0
        val coordinator = ProfileCoordinator(store, { null }, { owner }, {}, { starts++ })
        coordinator.restore(); val previous = checkNotNull(coordinator.currentLease())
        assertFalse(coordinator.transitionToSetup(previous) { owner = null; error("local cleanup unavailable") })
        assertTrue(coordinator.state.value is ProfileState.Blocked)
        assertNull(coordinator.currentLease()); assertEquals(1, starts)
        assertEquals(ProfileRole.CHILD, store.role)
    }
    @Test fun canceledRecoveryStopsPartiallyStartedChildRuntime() = runTest {
        var starts = 0; var running = false
        val coordinator = ProfileCoordinator(Store(), { null }, { "device" }, { running = false }, {
            running = true
            if (++starts == 2) throw CancellationException("recovery canceled")
        })
        coordinator.restore(); val previous = checkNotNull(coordinator.currentLease())
        try { coordinator.transitionToSetup(previous) { false }; fail() } catch (_: CancellationException) { }
        assertFalse(running)
        assertTrue(coordinator.state.value is ProfileState.Blocked)
        assertNull(coordinator.currentLease())
    }
}
