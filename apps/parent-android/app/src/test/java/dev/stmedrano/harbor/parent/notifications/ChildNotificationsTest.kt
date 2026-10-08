package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.child.*
import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChildNotificationsTest {
    private val binding = ChildBinding(DEVICE, FAMILY, CHILD)
    private val lease = ProfileLease(ProfileRole.CHILD, DEVICE, 1)
    private val data = mapOf("route" to envelope().getValue("route"))
    private inner class Harness {
        var current: ProfileLease? = lease
        var owned: ChildBinding? = binding
        var opted: ChildBinding? = binding
        var result: ChildSyncState = ChildSyncState.Fresh(1000, 2)
        var gate: CompletableDeferred<ChildSyncState>? = null
        var calls = 0
        val rendered = mutableListOf<ParentRoute>()
        var cleared = 0
        val controller = ChildNotifications({ current }, { owned }, { opted },
            { expected -> assertEquals(owned, expected); calls++; gate?.await() ?: result },
            { rendered += it }, { cleared++ }, { 1_234_000L })
    }
    @Test fun ownedHintRequiresSignedFreshReadBeforeGenericNotificationAndReceipt() = runTest {
        val h = Harness()
        assertTrue(h.controller.onMessage(data, lease))
        assertEquals(1, h.calls); assertEquals(1, h.rendered.size)
        assertEquals(EVENT, h.controller.receipt.value?.route?.resourceId)
        assertEquals(1_234_000L, h.controller.receipt.value?.receivedAt)
        assertFalse(h.controller.onMessage(data, lease))
        assertEquals(1, h.calls)
    }
    @Test fun foreignOrInactiveHintCannotReadOrPublish() = runTest {
        val h = Harness()
        assertFalse(h.controller.onMessage(data.mapValues { it.value.replace(FAMILY, EVENT) }, lease))
        assertFalse(h.controller.onMessage(envelope(), lease))
        h.current = null
        assertFalse(h.controller.onMessage(data, lease))
        h.current = lease; h.opted = null
        assertFalse(h.controller.onMessage(data, lease))
        assertEquals(0, h.calls); assertTrue(h.rendered.isEmpty()); assertNull(h.controller.receipt.value)
    }
    @Test fun staleOrRevokedReadIsNotDeliveryEvidence() = runTest {
        val h = Harness(); h.result = ChildSyncState.Stale(1000)
        assertFalse(h.controller.onMessage(data, lease))
        h.result = ChildSyncState.Blocked(ChildFailure.REVOKED)
        assertFalse(h.controller.onMessage(data, lease))
        assertTrue(h.rendered.isEmpty()); assertNull(h.controller.receipt.value)
        assertEquals(2, h.calls)
    }
    @Test fun lateReadAfterRoleChangeCannotRender() = runTest {
        val h = Harness(); val gate = CompletableDeferred<ChildSyncState>(); h.gate = gate
        val message = launch { assertFalse(h.controller.onMessage(data, lease)) }
        runCurrent(); assertEquals(1, h.calls)
        h.current = ProfileLease(ProfileRole.PARENT, REGISTRATION, 2)
        gate.complete(ChildSyncState.Fresh(1000, 2)); message.join()
        assertTrue(h.rendered.isEmpty()); assertNull(h.controller.receipt.value)
    }
    @Test fun invalidationFencesPendingReadAndClearsOnlyMemory() = runTest {
        val h = Harness(); val gate = CompletableDeferred<ChildSyncState>(); h.gate = gate
        val message = launch { assertFalse(h.controller.onMessage(data, lease)) }
        runCurrent(); assertEquals(1, h.calls)
        h.controller.invalidate()
        gate.complete(ChildSyncState.Fresh(1000, 2)); message.join()
        assertEquals(binding, h.opted)
        assertEquals(1, h.cleared); assertNull(h.controller.receipt.value)
    }
    private class Values : AuthValues {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun clear() { data.clear() }
    }
    @Test fun durableOptInStoresOnlyItsExactBindingAndRejectsExtraTokenFields() {
        val values = Values(); val store = ChildNotificationStore(values)
        store.setOpted(binding, true)
        assertEquals(binding, ChildNotificationStore(values).optedBinding())
        assertEquals(setOf("opt-in"), values.data.keys)
        assertFalse(values.data.values.single().contains("token"))
        values.data["opt-in"] = values.data.values.single().dropLast(1) + ",\"token\":\"sensitive\"}"
        assertNull(store.optedBinding())
    }
    @Test fun disablingOptInDoesNotDeleteOtherStoredInstallationMetadata() {
        val values = Values(); val store = ChildNotificationStore(values)
        values.write("installation", "stable-reference")
        store.setOpted(binding, true); store.setOpted(binding, false)
        assertNull(store.optedBinding())
        assertEquals("stable-reference", values.read("installation"))
    }
}
