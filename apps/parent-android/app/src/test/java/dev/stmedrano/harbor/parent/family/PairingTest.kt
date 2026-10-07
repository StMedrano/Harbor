package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PairingTest {
    private val parent = ParentIdentity("parent-a", "session-a")

    @Test fun issuanceIsNotEnrollmentAndExpiredCodeCanRenew() = runTest {
        var now = 1_000L
        var issued = 0
        val api = object : TestParentApi() {
            override suspend fun createPairing(childId: String): PairingCode {
                issued++
                return if (issued == 1) PairingCode("123456", "1970-01-01T00:00:03Z")
                    else PairingCode("654321", "1970-01-01T00:00:06Z")
            }
        }
        val model = PairingModel(api, { parent }) { now }
        model.issue(parent, "child-a")
        assertFalse(model.state.value.enrolled)
        assertFalse(model.expired())
        now = 3_000
        assertTrue(model.expired())
        model.issue(parent, "child-a")
        assertEquals("654321", model.state.value.code?.code)
        assertFalse(model.expired())
        assertFalse(model.state.value.enrolled)
    }

    @Test fun onlyFreshAuthorizedActiveDeviceReadConfirmsEnrollment() = runTest {
        val model = PairingModel(TestParentApi(), { parent }) { 1_000L }
        model.issue(parent, "child-a")
        model.observe(parent, snapshot(), cached = true)
        assertFalse(model.state.value.enrolled)
        val device = DevicePublicV1(1, "device", "family-a", "child-a", "Phone", "standard", "active", null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        model.observe(parent, snapshot(devices = listOf(device)), cached = true)
        assertFalse(model.state.value.enrolled)
        model.observe(parent, snapshot(devices = listOf(device.copy(status = "revoked"))), cached = false)
        assertFalse(model.state.value.enrolled)
        model.observe(parent, snapshot(devices = listOf(device)), cached = false)
        assertTrue(model.state.value.enrolled)
    }
}
