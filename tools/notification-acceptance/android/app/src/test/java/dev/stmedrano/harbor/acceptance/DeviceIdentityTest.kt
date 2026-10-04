package dev.stmedrano.harbor.acceptance

import org.junit.Assert.*
import org.junit.Test

class DeviceIdentityTest {
    @Test fun expiredAnonymousSessionRefreshesBeforeRequest() {
        val stages = mutableListOf<String>()
        val identity = DeviceIdentity(
            clock = { 100L },
            refresh = { token -> assertEquals("refresh-old", token); stages.add("refresh"); ChildSession("fresh", "refresh-new", 200L, true) },
            save = { stages.add("persist") }
        )
        identity.acceptSession(ChildSession("old", "refresh-old", 99L, true))
        stages.clear()
        assertEquals("fresh", identity.accessToken())
        stages.add("request")
        assertEquals(listOf("refresh", "persist", "request"), stages)
    }
    @Test fun rejectsParentSessionAndMalformedBinding() {
        val identity = DeviceIdentity({100L}, {throw IllegalStateException("No refresh expected")}, {})
        assertThrows(IllegalArgumentException::class.java) { identity.acceptSession(ChildSession("parent", "refresh", 200L, false)) }
        assertThrows(IllegalArgumentException::class.java) { identity.acceptBinding(DeviceBinding("invalid", "invalid", "invalid")) }
        assertNull(identity.binding)
    }
    @Test fun refreshFailureDoesNotReturnExpiredCredentials() {
        val identity = DeviceIdentity({100L}, {throw IllegalStateException("Retry enrollment")}, {})
        identity.acceptSession(ChildSession("old", "refresh", 99L, true))
        assertThrows(IllegalStateException::class.java) { identity.accessToken() }
    }
}
