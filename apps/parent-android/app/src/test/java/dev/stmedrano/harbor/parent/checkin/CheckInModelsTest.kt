package dev.stmedrano.harbor.parent.checkin

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CheckInModelsTest {
    private val now = java.time.Instant.parse("2026-10-20T16:00:00Z").toEpochMilli()
    private val raw = checkNotNull(javaClass.classLoader?.getResource("checkin-reply-v1.json")).readText()
    private val reply = Json.decodeFromString<CheckInReplyV1>(raw)
    private fun rejected(value: CheckInReplyV1) = assertThrows(IllegalArgumentException::class.java) { validateCheckInReply(value, now) }

    @Test fun sharedFixtureRoundTripsAndValidates() {
        assertEquals(reply, validateCheckInReply(reply, now))
        assertEquals(reply, Json.decodeFromString<CheckInReplyV1>(Json.encodeToString(CheckInReplyV1.serializer(), reply)))
        assertEquals(CheckInPreset.OK, reply.preset)
    }
    @Test fun anAbsentPlaceIsSentAsExplicitNull() {
        val encoded = Json.encodeToString(CheckInReplyV1.serializer(), reply.copy(fix = null))
        assertTrue(encoded, encoded.contains("\"fix\":null"))
        validateCheckInReply(reply.copy(fix = null), now)
    }
    @Test fun placeMustBeRecentBoundedAndNotFromTheFuture() {
        val fix = checkNotNull(reply.fix)
        rejected(reply.copy(fix = fix.copy(at = "2026-10-20T14:59:59Z")))
        rejected(reply.copy(fix = fix.copy(at = "2026-10-20T16:05:01Z")))
        validateCheckInReply(reply.copy(fix = fix.copy(at = "2026-10-20T15:00:00Z")), now)
        for (bad in listOf(fix.copy(latitude = 90.5), fix.copy(longitude = -181.0), fix.copy(accuracyM = -1), fix.copy(latitude = 41.8781141), fix.copy(longitude = Double.NaN)))
            rejected(reply.copy(fix = bad))
    }
    @Test fun versionAndRequestIdAreChecked() {
        rejected(reply.copy(version = 2)); rejected(reply.copy(requestId = "nope"))
    }
    @Test fun unknownPresetsAndExtraFieldsDoNotDecode() {
        assertThrows(Exception::class.java) { Json.decodeFromString<CheckInReplyV1>(raw.replace("\"ok\"", "\"help\"")) }
        assertThrows(Exception::class.java) { Json.decodeFromString<CheckInReplyV1>(raw.replaceFirst("{", "{\"text\":\"hi\",")) }
    }
    @Test fun stateReplyIsValidatedAndNewestFirst() {
        val id = reply.requestId
        val ok = """{"pending":{"requestId":"$id","requestedAt":"2026-10-20T16:00:00Z","expiresAt":"2026-10-21T16:00:00Z"},"recent":[
            {"requestId":"$id","requestedAt":"2026-10-19T10:00:00Z","repliedAt":"2026-10-19T10:05:00Z","preset":"ok","located":true},
            {"requestId":"$id","requestedAt":"2026-10-18T10:00:00Z","repliedAt":"2026-10-18T10:05:00Z","preset":"late","located":false}]}"""
        val state = decodeCheckInState(ok)
        assertEquals(CheckInPreset.LATE, state.recent[1].preset)
        assertThrows(IllegalArgumentException::class.java) {
            decodeCheckInState(ok.replace("2026-10-21T16:00:00Z", "2026-10-22T16:00:01Z"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            decodeCheckInState("""{"pending":null,"recent":[
              {"requestId":"$id","requestedAt":"2026-10-18T10:00:00Z","repliedAt":"2026-10-18T10:05:00Z","preset":"ok","located":true},
              {"requestId":"$id","requestedAt":"2026-10-19T10:00:00Z","repliedAt":"2026-10-19T10:05:00Z","preset":"ok","located":true}]}""")
        }
        assertNull(decodeCheckInState("""{"pending":null,"recent":[]}""").pending)
    }
    @Test fun receiptMustConfirm() {
        decodeCheckInReceipt("""{"confirmed":true,"receivedAt":"2026-10-20T16:00:01Z"}""")
        assertThrows(IllegalStateException::class.java) { decodeCheckInReceipt("""{"confirmed":false,"receivedAt":"2026-10-20T16:00:01Z"}""") }
    }
    @Test fun readReplyMatchesStatusAndReply() {
        val id = reply.requestId
        val answered = """{"requestId":"$id","requestedAt":"2026-10-20T10:00:00Z","expiresAt":"2026-10-21T10:00:00Z","status":"answered","reply":{"repliedAt":"2026-10-20T10:03:00Z","preset":"late","fix":null}}"""
        val pending = """{"requestId":"$id","requestedAt":"2026-10-19T10:00:00Z","expiresAt":"2026-10-20T10:00:00Z","status":"expired","reply":null}"""
        assertEquals(2, decodeCheckInRead("""{"checkins":[$answered,$pending]}""").checkins.size)
        assertThrows(IllegalArgumentException::class.java) { decodeCheckInRead("""{"checkins":[${pending.replace("\"reply\":null", "\"reply\":{\"repliedAt\":\"2026-10-19T11:00:00Z\",\"preset\":\"ok\",\"fix\":null}")}]}""") }
        assertThrows(Exception::class.java) { decodeCheckInRead("""{"checkins":[${answered.replace("\"reply\":{\"repliedAt\":\"2026-10-20T10:03:00Z\",\"preset\":\"late\",\"fix\":null}", "\"reply\":null")}]}""") }
    }
}
