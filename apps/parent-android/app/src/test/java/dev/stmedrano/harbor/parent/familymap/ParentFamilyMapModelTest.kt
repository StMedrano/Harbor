package dev.stmedrano.harbor.parent.familymap

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ParentFamilyMapModelTest {
    private val me = ParentIdentity("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222")
    private val family = "33333333-3333-4333-8333-333333333333"
    private val nowMs = java.time.Instant.parse("2026-10-20T16:00:00Z").toEpochMilli()
    private var identity: ParentIdentity? = me
    private var failure: Exception? = null
    private var sharing: MySharing? = null
    private var childrenCanView = true
    private val calls = mutableListOf<String>()
    private val receipt = ParentLocationReceiptV1(true, "2026-10-20T16:00:01Z")
    private val api = object : ParentFamilyMapApi {
        private fun check() { failure?.let { throw it } }
        override suspend fun settings(familyId: String): FamilyMapSettingsV1 { calls += "settings"; check(); return FamilyMapSettingsV1(childrenCanView, sharing) }
        override suspend fun setChildrenCanView(familyId: String, value: Boolean): FamilyMapSettingsV1 { calls += "set:$value"; check(); childrenCanView = value; return FamilyMapSettingsV1(childrenCanView, sharing) }
        override suspend fun report(report: ParentLocationReportV1): ParentLocationReceiptV1 { calls += "report:${report.label}"; check(); sharing = MySharing(report.label, report.fix.at); return receipt }
        override suspend fun stop(familyId: String): ParentLocationReceiptV1 { calls += "stop"; check(); sharing = null; return receipt }
    }
    private val model = ParentFamilyMapModel(api, { identity }, { nowMs })
    private val fix = FamilyMapFix("2026-10-20T15:58:00Z", 41.5, -87.6, 12)

    @Test fun nothingIsClaimedBeforeTheServerAnswers() = runTest {
        assertFalse(model.state.value.loaded)
        model.load(me, family)
        assertTrue(model.state.value.loaded)
        assertTrue(model.state.value.childrenCanView)
        assertNull(model.state.value.mySharing)
    }
    @Test fun aFailedLoadStaysNotLoadedAndSaysWhy() = runTest {
        failure = java.io.IOException("offline")
        model.load(me, family)
        assertFalse(model.state.value.loaded)
        assertEquals(FamilyMapProblem.COULD_NOT_LOAD, model.state.value.problem)
    }
    @Test fun turningTheChildrenSwitchOffIsSavedThenShown() = runTest {
        model.load(me, family)
        model.setChildrenCanView(me, family, false)
        assertFalse(model.state.value.childrenCanView)
        assertEquals("set:false", calls.last())
    }
    @Test fun aFailedSaveKeepsTheOldValueAndSaysSo() = runTest {
        model.load(me, family)
        failure = java.io.IOException("offline")
        model.setChildrenCanView(me, family, false)
        assertTrue(model.state.value.childrenCanView)
        assertEquals(FamilyMapProblem.COULD_NOT_SAVE, model.state.value.problem)
        assertFalse(model.state.value.busy)
    }
    @Test fun sharingStoresTheLabelAndStopDeletesIt() = runTest {
        model.load(me, family)
        assertTrue(model.share(me, family, "Alex", fix, interactive = true))
        assertEquals(MySharing("Alex", fix.at), model.state.value.mySharing)
        model.stop(me, family)
        assertNull(model.state.value.mySharing)
        assertEquals(listOf("settings", "report:Alex", "stop"), calls)
    }
    @Test fun aStaleOrBadPlaceIsNeverSent() = runTest {
        model.load(me, family)
        assertFalse(model.share(me, family, "Alex", fix.copy(at = "2026-10-20T15:40:00Z"), interactive = true))
        assertFalse(model.share(me, family, " ", fix, interactive = true))
        assertEquals(listOf("settings"), calls)
        assertEquals(FamilyMapProblem.COULD_NOT_SHARE, model.state.value.problem)
    }
    @Test fun quietRefreshesStaySilentWhenTheyFail() = runTest {
        model.load(me, family)
        failure = java.io.IOException("offline")
        assertFalse(model.share(me, family, "Alex", fix, interactive = false))
        assertNull(model.state.value.problem)
    }
    @Test fun anInteractiveFailureIsShown() = runTest {
        model.load(me, family)
        failure = java.io.IOException("offline")
        assertFalse(model.share(me, family, "Alex", fix, interactive = true))
        assertEquals(FamilyMapProblem.COULD_NOT_SHARE, model.state.value.problem)
        assertFalse(model.state.value.busy)
    }
    @Test fun aReplyAfterSignOutIsDropped() = runTest {
        model.load(me, family)
        identity = null
        model.setChildrenCanView(me, family, false)
        assertTrue(model.state.value.childrenCanView)
    }
    @Test fun lostAccessLeavesNothingShown() = runTest {
        failure = FamilyAccessDenied()
        model.load(me, family)
        assertFalse(model.state.value.loaded)
        assertNull(model.state.value.mySharing)
    }
    @Test fun changesBeforeTheFirstAnswerAreIgnored() = runTest {
        model.setChildrenCanView(me, family, false)
        assertFalse(model.share(me, family, "Alex", fix, interactive = true))
        model.stop(me, family)
        assertTrue(calls.isEmpty())
    }
    @Test fun clearForgetsEverything() = runTest {
        model.load(me, family)
        model.clear()
        assertEquals(ParentFamilyMapState(), model.state.value)
    }
}
