package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProfileNotificationRouterTest {
    private val child = ProfileLease(ProfileRole.CHILD, DEVICE, 1)
    private val parent = ProfileLease(ProfileRole.PARENT, REGISTRATION, 2)
    private val childData = mapOf("route" to envelope().getValue("route"))
    private inner class Harness {
        var active: ProfileLease? = child
        var binding: ChildBinding? = ChildBinding(DEVICE, FAMILY, CHILD)
        var allowed = true
        var opted = true
        var token = "ephemeral-token-one"
        var tokenGate: CompletableDeferred<String>? = null
        var providerCalls = 0
        var parentCalls = 0
        var offline = false
        var registrationGate: CompletableDeferred<Boolean>? = null
        val registered = mutableListOf<Pair<ProfileLease, String>>()
        val queued = mutableListOf<Pair<ProfileLease, Map<String, String>>>()
        val hidden = mutableListOf<ProfileLease>()
        val router = ProfileNotificationRouter({ active }, { binding }, { opted }, { allowed },
            { lease, data -> queued += lease to data; true },
            { parentCalls++; true },
            { providerCalls++; tokenGate?.await() ?: token },
            { lease, value -> registered += lease to value; if (offline) throw IOException("provider detail must not become status"); registrationGate?.await() ?: true },
            { hidden += it })
    }
    @Test fun wrongProfileDenied() {
        val h = Harness()
        assertFalse(h.router.accept(envelope(), child))
        assertTrue(h.router.accept(childData, child))
        assertFalse(h.router.accept(childData + ("token" to "sensitive"), child))
        assertFalse(h.router.accept(childData.mapValues { it.value.replace(FAMILY, EVENT) }, child))
        assertFalse(h.router.accept(childData.mapValues { it.value.replace("device.state.changed", "unknown.kind") }, child))
        h.active = parent
        assertFalse(h.router.accept(childData, parent))
        assertTrue(h.router.accept(envelope(), parent))
        assertEquals(listOf(child, parent), h.queued.map { it.first })
    }
    @Test fun rotationPreservesActiveOwner() = runTest {
        val h = Harness()
        h.router.syncToken(child)
        h.token = "ephemeral-token-two"
        h.router.syncToken(child)
        assertEquals(listOf(child to "ephemeral-token-one", child to "ephemeral-token-two"), h.registered)
        assertTrue(h.router.state.value.confirmed)
        assertEquals(child, h.router.state.value.lease)
        assertFalse(h.router.state.value.toString().contains("ephemeral-token"))
        assertEquals(0, h.parentCalls)
    }
    @Test fun lateTokenAfterTransitionIgnored() = runTest {
        val h = Harness(); val gate = CompletableDeferred<String>(); h.tokenGate = gate
        val work = launch { h.router.syncToken(child) }
        runCurrent()
        assertEquals(1, h.providerCalls)
        h.active = parent
        gate.complete("late-token")
        work.join()
        assertTrue(h.registered.isEmpty())
        assertFalse(h.router.state.value.confirmed)
    }
    @Test fun oldJobCannotRegister() = runTest {
        val h = Harness(); h.active = child.copy(generation = 2)
        h.router.syncToken(child)
        assertEquals(0, h.providerCalls)
        assertFalse(h.router.accept(childData, child))
        assertTrue(h.registered.isEmpty())
    }
    @Test fun tapCannotChangeRole() {
        val h = Harness(); h.active = parent
        assertFalse(h.router.accept(childData, child))
        assertEquals(parent, h.active)
        assertTrue(h.queued.isEmpty())
    }
    @Test fun coldStartUsesValidatedBinding() = runTest {
        val h = Harness(); h.active = null
        assertFalse(h.router.accept(childData, child))
        h.router.syncToken(child)
        assertEquals(0, h.providerCalls)
        h.active = child
        assertTrue(h.router.accept(childData, child))
        h.binding = h.binding!!.copy(deviceId = EVENT)
        assertFalse(h.router.accept(childData, child))
        h.router.syncToken(child)
        assertEquals(0, h.providerCalls)
    }
    @Test fun offlineRegistrationUnconfirmed() = runTest {
        val h = Harness(); h.offline = true
        h.router.syncToken(child)
        assertEquals(1, h.registered.size)
        assertFalse(h.router.state.value.confirmed)
        assertFalse(h.router.state.value.busy)
        assertFalse(h.router.state.value.toString().contains("provider detail"))
    }
    @Test fun permissionAndOptInPrecedeProviderRequest() = runTest {
        val h = Harness(); h.allowed = false
        h.router.syncToken(child)
        h.allowed = true; h.opted = false
        h.router.syncToken(child)
        assertEquals(0, h.providerCalls)
        assertTrue(h.registered.isEmpty())
    }
    @Test fun stopCancelsOldWorkWithoutHidingNewOwner() = runTest {
        val h = Harness(); val gate = CompletableDeferred<String>(); h.tokenGate = gate
        val old = launch { h.router.syncToken(child) }
        runCurrent()
        assertEquals(1, h.providerCalls)
        val fresh = child.copy(ownerId = EVENT, generation = 2)
        h.active = fresh; h.binding = h.binding!!.copy(deviceId = EVENT); h.tokenGate = null
        h.router.stop(child)
        old.join()
        h.router.syncToken(fresh)
        h.router.stop(child)
        assertTrue(old.isCancelled)
        assertEquals(fresh, h.router.state.value.lease)
        assertTrue(h.router.state.value.confirmed)
        assertTrue(h.hidden.isEmpty())
        assertEquals(listOf(fresh to "ephemeral-token-one"), h.registered)
    }
    @Test fun sameOwnerRotationSerializesProviderAndRegistration() = runTest {
        val h = Harness(); val gate = CompletableDeferred<String>(); h.tokenGate = gate
        val first = launch { h.router.syncToken(child) }
        runCurrent()
        assertEquals(1, h.providerCalls)
        h.tokenGate = null; h.token = "rotated-token"
        val second = launch { h.router.syncToken(child) }
        runCurrent()
        assertEquals(1, h.providerCalls)
        assertTrue(h.registered.isEmpty())
        gate.complete("original-token")
        first.join(); second.join()
        assertEquals(listOf(child to "original-token", child to "rotated-token"), h.registered)
        assertTrue(h.router.state.value.confirmed)
    }
    @Test fun parentTokenWorkDelegatesWithoutUsingChildProvider() = runTest {
        val h = Harness(); h.active = parent
        h.router.syncToken(parent)
        assertEquals(1, h.parentCalls)
        assertEquals(0, h.providerCalls)
        assertTrue(h.registered.isEmpty())
        assertEquals(parent, h.router.state.value.lease)
        assertTrue(h.router.state.value.confirmed)
    }
    @Test fun lateBackendConfirmationCannotPublishForNewProfile() = runTest {
        val h = Harness(); val gate = CompletableDeferred<Boolean>(); h.registrationGate = gate
        val work = launch { h.router.syncToken(child) }
        runCurrent()
        assertEquals(1, h.registered.size)
        h.active = parent
        gate.complete(true)
        work.join()
        assertFalse(h.router.state.value.confirmed)
        assertNull(h.router.state.value.lease)
    }
}
