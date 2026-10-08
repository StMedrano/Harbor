package dev.stmedrano.harbor.parent.profile

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProfileTransitionTest {
    private class Store : ProfileStore {
        var role: ProfileRole? = null
        override suspend fun read() = role
        override suspend fun write(role: ProfileRole) { this.role = role }
        override suspend fun clear() { role = null }
    }
    @Test fun cleanupRunsOnlyAfterLeaseIsHiddenAndRuntimeStopped() = runTest {
        val store = Store(); var stopped = false
        val value = ProfileCoordinator(store, { "parent" }, { null }, { stopped = true }, { stopped = false })
        value.restore(); val lease = checkNotNull(value.currentLease())
        assertTrue(value.transitionToSetup(lease) {
            assertNull(value.currentLease()); assertTrue(stopped)
            assertEquals(ProfileRole.PARENT, store.role)
            true
        })
        assertEquals(ProfileState.Setup, value.state.value); assertNull(store.role)
    }
    @Test fun failedCleanupRetainsHintAndBlocksOtherRole() = runTest {
        val store = Store()
        val value = ProfileCoordinator(store, { "parent" }, { null }, {}, {})
        value.restore(); val lease = checkNotNull(value.currentLease())
        assertFalse(value.transitionToSetup(lease) { false })
        assertTrue(value.state.value is ProfileState.Blocked)
        assertNull(value.currentLease()); assertEquals(ProfileRole.PARENT, store.role)
    }
    @Test fun obsoleteLeaseCannotRunCleanup() = runTest {
        val value = ProfileCoordinator(Store(), { "parent" }, { null }, {}, {})
        value.restore(); val old = checkNotNull(value.currentLease()); value.restore()
        val current = value.currentLease(); var cleaned = false
        assertFalse(value.transitionToSetup(old) { cleaned = true; true })
        assertFalse(cleaned); assertEquals(current, value.currentLease())
    }
}
