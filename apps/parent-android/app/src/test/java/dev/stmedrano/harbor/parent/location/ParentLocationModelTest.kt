package dev.stmedrano.harbor.parent.location

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ParentLocationModelTest {
    private val nowMs = java.time.Instant.parse("2026-10-10T18:00:00Z").toEpochMilli()
    private val parentA = ParentIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "session-a")
    private val parentB = ParentIdentity("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "session-b")
    private val device = "22222222-2222-4222-8222-222222222222"
    private fun fix(at: String) = LocationFix(at, 41.878114, -87.629798, 12, LocationProvider.GPS)
    private fun reply(vararg at: String, status: LocationStatus = LocationStatus.SHARING, received: String = "2026-10-10T17:59:30Z") =
        LocationReadReplyV1(LocationReadState.AVAILABLE, status, "2026-10-10T17:59:00Z", received, at.map(::fix))

    @Test fun availableReplyBecomesAViewWithNewestFirst() = runTest {
        val model = ParentLocationModel({ reply("2026-10-10T17:59:00Z", "2026-10-10T17:50:00Z") }, { parentA }, { nowMs })
        model.load(parentA, device)
        val view = checkNotNull(model.state.value).view
        assertEquals(LocationViewStatus.AVAILABLE, view.status)
        assertEquals("2026-10-10T17:59:00Z", view.latest?.at)
        assertFalse(view.isStale(nowMs))
        assertTrue(view.isStale(nowMs + LOCATION_STALE_AFTER_MS + 31_000))
    }

    @Test fun failureKeepsPreviousPlaceMarkedOfflineAndAccessLossDropsIt() = runTest {
        var mode = 0
        val model = ParentLocationModel({ when (mode) { 0 -> reply("2026-10-10T17:59:00Z"); 1 -> throw java.io.IOException("offline"); else -> throw FamilyAccessDenied() } }, { parentA }, { nowMs })
        model.load(parentA, device)
        mode = 1; model.load(parentA, device)
        assertTrue(checkNotNull(model.state.value).view.offline)
        assertNotNull(model.state.value?.view?.latest)
        mode = 2; model.load(parentA, device)
        assertEquals(LocationViewStatus.ACCESS_LOST, model.state.value?.view?.status)
        assertNull(model.state.value?.view?.latest)
        mode = 1; model.load(parentA, device)
        assertEquals("no stale place returns after access loss", LocationViewStatus.LOAD_FAILED, model.state.value?.view?.status)
    }

    @Test fun repliesForAnotherIdentityOrAfterClearAreDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        var identity: ParentIdentity? = parentA
        val model = ParentLocationModel({ gate.await(); reply("2026-10-10T17:59:00Z") }, { identity }, { nowMs })
        val job = async { model.load(parentA, device) }
        identity = parentB
        gate.complete(Unit); job.await()
        assertNull(model.state.value)
        identity = parentA
        model.load(parentA, device)
        assertNotNull(model.state.value)
        model.clear()
        assertNull(model.state.value)
    }

    @Test fun newerLoadWinsOverSlowerEarlierLoad() = runTest {
        val slow = CompletableDeferred<Unit>()
        var calls = 0
        val model = ParentLocationModel({ val n = ++calls; if (n == 1) { slow.await(); reply("2026-10-10T17:00:00Z") } else reply("2026-10-10T17:59:00Z") }, { parentA }, { nowMs })
        val first = async { model.load(parentA, device) }
        kotlinx.coroutines.yield()
        model.load(parentA, device)
        slow.complete(Unit); first.await()
        assertEquals("2026-10-10T17:59:00Z", model.state.value?.view?.latest?.at)
    }

    @Test fun messagesAndAges() {
        assertNull(locationStatusMessage(LocationStatus.SHARING))
        assertTrue(locationStatusMessage(LocationStatus.PERMISSION_LOST)!!.contains("permission"))
        assertTrue(locationStatusMessage(LocationStatus.SERVICES_OFF)!!.contains("switched off"))
        assertTrue(locationStatusMessage(LocationStatus.BACKGROUND_MISSING)!!.contains("open"))
        assertEquals("just now", locationAgeLabel(20_000))
        assertEquals("5 min ago", locationAgeLabel(5 * 60_000))
        assertEquals("3 h ago", locationAgeLabel(3 * 3_600_000L))
        assertEquals("3 days ago", locationAgeLabel(3 * 86_400_000L))
    }

    @Test fun invalidRepliesAreRejectedBeforeDisplay() {
        assertThrows(IllegalArgumentException::class.java) { validateLocationRead(reply("2026-10-10T17:50:00Z", "2026-10-10T17:59:00Z")) }
        assertThrows(IllegalArgumentException::class.java) {
            validateLocationRead(LocationReadReplyV1(LocationReadState.NONE, LocationStatus.SHARING, null, null, emptyList()))
        }
        validateLocationRead(LocationReadReplyV1(LocationReadState.EXPIRED, null, null, null, emptyList()))
    }
}
