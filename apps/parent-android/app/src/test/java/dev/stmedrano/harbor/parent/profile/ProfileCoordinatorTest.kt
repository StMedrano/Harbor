package dev.stmedrano.harbor.parent.profile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProfileCoordinatorTest {
    private class Store(var role: ProfileRole? = null) : ProfileStore {
        override suspend fun read() = role
        override suspend fun write(role: ProfileRole) { this.role = role }
        override suspend fun clear() { role = null }
    }
    private fun coordinator(store: Store = Store(), parent: suspend () -> String? = { null },
        child: suspend () -> String? = { null }) = ProfileCoordinator(store, parent, child, {}, {})

    @Test fun freshInstallIsSetup() = runTest {
        val value = coordinator(); value.restore()
        assertEquals(ProfileState.Setup, value.state.value)
        assertNull(value.currentLease())
    }
    @Test fun parent04WithoutModeRestoresParent() = runTest {
        val store = Store(); val value = coordinator(store, parent = { "verified-parent" })
        value.restore()
        assertTrue(value.state.value is ProfileState.Parent)
        assertEquals("verified-parent", value.currentLease()?.ownerId)
        assertEquals(ProfileRole.PARENT, store.role)
    }
    @Test fun bothProfilesFailClosed() = runTest {
        val value = coordinator(parent = { "parent" }, child = { "device" })
        value.restore()
        assertTrue(value.state.value is ProfileState.Blocked)
        assertNull(value.currentLease())
    }
    @Test fun hintCannotGrantDashboard() = runTest {
        val value = coordinator(Store(ProfileRole.CHILD)); value.restore()
        assertTrue(value.state.value is ProfileState.Blocked)
        assertNull(value.currentLease())
    }
    @Test fun selectedChildCannotActivateParentCredentials() = runTest {
        val value = coordinator(parent = { "parent" })
        value.activateChild()
        assertTrue(value.state.value is ProfileState.Blocked)
        assertNull(value.currentLease())
    }
    @Test fun validationFailureNeverStartsRuntime() = runTest {
        var started = false
        val value = ProfileCoordinator(Store(), { error("private-error") }, { null }, {}, { started = true })
        value.restore()
        assertFalse(started)
        assertEquals(ProfileState.Blocked(ProfileBlock.STORAGE_UNAVAILABLE), value.state.value)
    }
    @Test fun replacementHidesLeaseBeforeStoppingRuntime() = runTest {
        val stopping = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        var stops = 0
        val value = ProfileCoordinator(Store(), { "parent" }, { null }, {
            if (++stops == 2) { stopping.complete(Unit); released.await() }
        }, {})
        value.restore(); val previous = checkNotNull(value.currentLease())
        val replacement = async { value.restore() }; stopping.await()
        assertNull(value.currentLease())
        assertFalse(value.isCurrent(previous))
        released.complete(Unit); replacement.await()
        assertFalse(value.isCurrent(previous))
    }
    @Test fun failedRuntimeStartStopsPartialRuntime() = runTest {
        var running = false
        val value = ProfileCoordinator(Store(), { "parent" }, { null }, { running = false }, {
            running = true; error("start failed")
        })
        value.restore()
        assertFalse(running)
        assertNull(value.currentLease())
        assertTrue(value.state.value is ProfileState.Blocked)
    }
    @Test fun lateOldRestoreCannotActivate() = runTest {
        val entered = CompletableDeferred<Unit>(); val old = CompletableDeferred<String?>()
        var calls = 0
        val value = coordinator(parent = {
            if (++calls == 1) { entered.complete(Unit); old.await() } else "current-parent"
        })
        val first = async { value.restore() }; entered.await()
        value.restore(); val current = checkNotNull(value.currentLease())
        old.complete("old-parent"); first.await()
        assertEquals("current-parent", value.currentLease()?.ownerId)
        assertTrue(value.isCurrent(current))
        assertFalse(value.isCurrent(current.copy(generation = current.generation - 1)))
    }
}
