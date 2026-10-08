package dev.stmedrano.harbor.parent.profile

import java.io.IOException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProfileRetryTest {
    private class Store(var role: ProfileRole?) : ProfileStore {
        var clears = 0
        override suspend fun read() = role
        override suspend fun write(role: ProfileRole) { this.role = role }
        override suspend fun clear() { clears++; role = null }
    }
    @Test fun retryRevalidatesOriginalParentWithoutClearingRecords() = runTest {
        val store = Store(ProfileRole.PARENT); var online = false; var starts = 0
        val value = ProfileCoordinator(store, { if (!online) throw IOException("offline"); "original-parent" }, { null }, {}, { starts++ })
        value.restore()
        assertEquals(ProfileState.Blocked(ProfileBlock.NETWORK_UNAVAILABLE), value.state.value)
        assertNull(value.currentLease()); assertEquals(0, starts)
        online = true; assertTrue(value.retryValidation())
        assertEquals(ProfileRole.PARENT, value.currentLease()?.role)
        assertEquals("original-parent", value.currentLease()?.ownerId)
        assertEquals(0, store.clears); assertEquals(1, starts)
    }
    @Test fun retryRevalidatesOriginalChildWithoutRoleEscape() = runTest {
        val store = Store(ProfileRole.CHILD); var online = false
        val value = ProfileCoordinator(store, { null }, { if (!online) throw IOException("offline"); "original-device" }, {}, {})
        value.restore(); assertNull(value.currentLease())
        online = true; assertTrue(value.retryValidation())
        assertEquals(ProfileRole.CHILD, value.currentLease()?.role)
        assertEquals("original-device", value.currentLease()?.ownerId)
        assertEquals(0, store.clears)
    }
    @Test fun ambiguityAndInvalidCredentialsCannotUseNetworkRetry() = runTest {
        val store = Store(ProfileRole.CHILD)
        val mixed = ProfileCoordinator(store, { "parent" }, { "device" }, {}, {})
        mixed.restore(); assertFalse(mixed.retryValidation()); assertNull(mixed.currentLease())
        val invalid = ProfileCoordinator(store, { null }, { throw ProfileValidationFailure(ProfileBlock.INVALID_CREDENTIALS) }, {}, {})
        invalid.restore(); assertFalse(invalid.retryValidation()); assertNull(invalid.currentLease())
        assertEquals(0, store.clears)
    }
    @Test fun retryTimeoutKeepsNetworkGuidanceAndCredentials() = runTest {
        val store = Store(ProfileRole.PARENT); var attempt = 0
        val value = ProfileCoordinator(store, { if (++attempt == 1) throw IOException("offline"); awaitCancellation() }, { null }, {}, {})
        value.restore(); assertFalse(value.retryValidation())
        assertEquals(ProfileState.Blocked(ProfileBlock.NETWORK_UNAVAILABLE), value.state.value)
        assertNull(value.currentLease()); assertEquals(0, store.clears)
    }
}
