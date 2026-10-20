package dev.stmedrano.harbor.parent.checkin

import dev.stmedrano.harbor.parent.child.UsageRequestRejected
import dev.stmedrano.harbor.parent.location.LocationFix
import dev.stmedrano.harbor.parent.location.LocationProvider
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChildCheckInModelTest {
    private val lease = ProfileLease(ProfileRole.CHILD, "22222222-2222-4222-8222-222222222222", 1)
    private val id = "5b8d2c40-7e19-4a63-9f05-1c3e6a8b2d74"
    private val t0 = java.time.Instant.parse("2026-10-20T16:00:00Z").toEpochMilli()
    private var nowMs = t0
    private var leaseCurrent = true
    private var fix: LocationFix? = LocationFix("2026-10-20T15:55:00Z", 41.878114, -87.629798, 18, LocationProvider.GPS)
    private val sent = mutableListOf<Pair<String, String>>()
    private var pendingJson = """{"requestId":"$id","requestedAt":"2026-10-20T15:58:00Z","expiresAt":"2026-10-21T15:58:00Z"}"""
    private var failReply: Exception? = null
    private var failState = false
    private val model = ChildCheckInModel({ _, op, body ->
        sent += op to body
        when (op) {
            "get-checkin-state" -> { if (failState) throw java.io.IOException("offline"); """{"pending":$pendingJson,"recent":[]}""" }
            "reply-checkin" -> { failReply?.let { throw it }; pendingJson = "null"; """{"confirmed":true,"receivedAt":"2026-10-20T16:00:01Z"}""" }
            else -> error("unexpected $op")
        }
    }, { fix }, { leaseCurrent }, { nowMs })

    @Test fun refreshShowsAnOpenRequestAndNothingBeforeItLoads() = runTest {
        assertFalse(model.state.value.loaded)
        model.refresh(lease)
        assertTrue(model.state.value.loaded)
        assertEquals(id, model.state.value.pending?.requestId)
        assertEquals("""{"version":1}""", sent.single().second)
    }
    @Test fun anExpiredRequestIsNotOffered() = runTest {
        nowMs = java.time.Instant.parse("2026-10-21T16:00:00Z").toEpochMilli()
        model.refresh(lease)
        assertNull(model.state.value.pending)
    }
    @Test fun replyCarriesThePresetAndTheKnownPlaceThenRefreshes() = runTest {
        model.refresh(lease)
        assertTrue(model.reply(lease, CheckInPreset.OK))
        val body = Json.decodeFromString<CheckInReplyV1>(sent.first { it.first == "reply-checkin" }.second)
        assertEquals(CheckInReplyV1(1, id, CheckInPreset.OK, CheckInFix("2026-10-20T15:55:00Z", 41.878114, -87.629798, 18)), body)
        assertNull(model.state.value.pending)
        assertFalse(model.state.value.sending)
        assertEquals("get-checkin-state", sent.last().first)
    }
    @Test fun aStalePlaceOrNoPlaceIsSentAsNull() = runTest {
        model.refresh(lease)
        fix = fix!!.copy(at = "2026-10-20T14:00:00Z")
        assertTrue(model.reply(lease, CheckInPreset.LATE))
        assertNull(Json.decodeFromString<CheckInReplyV1>(sent.first { it.first == "reply-checkin" }.second).fix)
    }
    @Test fun noPlaceKnownStillAnswers() = runTest {
        fix = null; model.refresh(lease)
        assertTrue(model.reply(lease, CheckInPreset.OK))
        assertTrue(sent.first { it.first == "reply-checkin" }.second.contains("\"fix\":null"))
    }
    @Test fun nothingIsSentWithoutAnOpenRequest() = runTest {
        pendingJson = "null"; model.refresh(lease)
        assertFalse(model.reply(lease, CheckInPreset.OK))
        assertTrue(sent.none { it.first == "reply-checkin" })
    }
    @Test fun aClosedRequestReloadsAndSaysSo() = runTest {
        model.refresh(lease)
        failReply = UsageRequestRejected(400, "VALIDATION_FAILED")
        pendingJson = "null"
        assertFalse(model.reply(lease, CheckInPreset.OK))
        assertEquals(CheckInProblem.REQUEST_ENDED, model.state.value.problem)
        assertNull(model.state.value.pending)
    }
    @Test fun aNetworkFailureKeepsTheRequestOpenForARetry() = runTest {
        model.refresh(lease)
        failReply = java.io.IOException("offline")
        assertFalse(model.reply(lease, CheckInPreset.OK))
        assertEquals(CheckInProblem.COULD_NOT_SEND, model.state.value.problem)
        assertEquals(id, model.state.value.pending?.requestId)
        assertFalse(model.state.value.sending)
        failReply = null
        assertTrue(model.reply(lease, CheckInPreset.OK))
        assertNull(model.state.value.problem)
    }
    @Test fun aFailedLoadKeepsWhatWasShownAndFlagsTheProblem() = runTest {
        model.refresh(lease)
        failState = true
        model.refresh(lease)
        assertEquals(CheckInProblem.COULD_NOT_LOAD, model.state.value.problem)
        assertEquals(id, model.state.value.pending?.requestId)
    }
    @Test fun aReplacedProfileChangesNothing() = runTest {
        leaseCurrent = false
        model.refresh(lease)
        assertTrue(sent.isEmpty())
        assertFalse(model.state.value.loaded)
        assertFalse(model.reply(lease, CheckInPreset.OK))
    }
    @Test fun aParentProfileCannotAnswer() = runTest {
        model.refresh(lease)
        assertFalse(model.reply(lease.copy(role = ProfileRole.PARENT), CheckInPreset.OK))
        assertTrue(sent.none { it.first == "reply-checkin" })
    }
}
