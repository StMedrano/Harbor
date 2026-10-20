package dev.stmedrano.harbor.parent.activity

import dev.stmedrano.harbor.parent.child.UsageRequestRejected
import dev.stmedrano.harbor.parent.controls.ControlEvent
import dev.stmedrano.harbor.parent.controls.ControlsBedtime
import dev.stmedrano.harbor.parent.controls.ControlsPause
import dev.stmedrano.harbor.parent.controls.ControlsV1
import dev.stmedrano.harbor.parent.controls.Weekday
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class ActivityRuntimeTest {
    private val lease = ProfileLease(ProfileRole.CHILD, "22222222-2222-4222-8222-222222222222", 1)
    private val t0 = Instant.parse("2026-10-20T16:00:00Z").toEpochMilli()
    private val exempt = setOf("com.android.launcher", "dev.stmedrano.harbor.parent")

    @Test fun sessionsAreJoinedFilteredAndOpenOnesWait() {
        val transitions = listOf(
            AppTransition(t0, "com.google.android.youtube", true),
            AppTransition(t0 + 120_000, "com.google.android.youtube", false),
            AppTransition(t0 + 122_000, "com.google.android.youtube", true), // 2 s gap: same visit
            AppTransition(t0 + 600_000, "com.google.android.youtube", false),
            AppTransition(t0 + 700_000, "com.example.quick", true),           // 30 s: too short
            AppTransition(t0 + 730_000, "com.example.quick", false),
            AppTransition(t0 + 800_000, "com.android.launcher", true),        // exempt
            AppTransition(t0 + 900_000, "com.android.launcher", false),
            AppTransition(t0 + 1_000_000, "com.example.game", true),          // still open
        )
        val (sessions, cursor) = sessionsFrom(transitions, t0, t0 + 1_200_000, exempt)
        assertEquals(listOf(AppSession("com.google.android.youtube", t0, t0 + 600_000)), sessions)
        assertEquals(t0 + 1_000_000, cursor)
        val (none, idle) = sessionsFrom(emptyList(), t0, t0 + 5_000, exempt)
        assertTrue(none.isEmpty()); assertEquals(t0 + 5_000, idle)
    }
    @Test fun eventsAreValidStableAndTextFree() {
        val s = AppSession("com.google.android.youtube", t0 + 400, t0 + 60_200)
        val event = sessionEvent(s, "YouTube")
        validateActivityEvent(event)
        assertEquals(event.id, sessionEvent(s, "YouTube").id)
        assertNotEquals(event.id, sessionEvent(s.copy(startMs = t0 + 5_000), "YouTube").id)
        val blocked = controlEvent(ControlEvent(ActivityKind.BLOCKED_OPEN, t0, "com.zhiliaoapp.musically", "TikTok", BlockReason.BLOCKED_APP))
        validateActivityEvent(blocked)
        val pause = controlEvent(ControlEvent(ActivityKind.PAUSE_STARTED, t0, "com.zhiliaoapp.musically", "TikTok", BlockReason.PAUSED))
        validateActivityEvent(pause)
        assertNull(pause.packageName); assertNull(pause.reason)
    }
    @Test fun eachStateChangeIsOneEventAndTheFirstPassIsABaseline() {
        val zone = ZoneId.of("America/Chicago")
        val noon = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, zone)
        val night = ControlsV1(bedtime = ControlsBedtime(true, "21:00", "07:00", Weekday.entries))
        val (first, s1) = stateChanges(ActivityStoredState("b", "e"), noon, night, true)
        assertTrue(first.isEmpty())
        val (atNine, s2) = stateChanges(s1, noon.withHour(21), night, true)
        assertEquals(listOf(ActivityKind.BEDTIME_STARTED), atNine.map { it.kind })
        val (again, s3) = stateChanges(s2, noon.withHour(22), night, true)
        assertTrue(again.isEmpty())
        val (off, _) = stateChanges(s3, noon.withHour(22), night.copy(pause = ControlsPause(true, null)), false)
        assertEquals(setOf(ActivityKind.PAUSE_STARTED, ActivityKind.PROTECTION_OFF), off.map { it.kind }.toSet())
    }

    private class MemoryStore : ActivityStore {
        var state: ActivityStoredState? = null
        override fun read(bindingId: String) = state?.takeIf { it.bindingId == bindingId }
        override fun update(bindingId: String, change: (ActivityStoredState) -> ActivityStoredState): ActivityStoredState {
            val next = change(read(bindingId) ?: ActivityStoredState(bindingId, "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b"))
            state = next; return next
        }
        override fun eraseBinding(bindingId: String) { state = null }
        override fun restoreCheckpoint(bindingId: String, reply: ActivityCheckpointReplyV1) =
            ActivityStoredState(bindingId, reply.epochId ?: "6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b", nextSequence = reply.sequence + 1).also { state = it }
    }
    @Test fun reporterUploadsOnceReplaysExactBytesAndRepairsAStaleSequence() = runTest {
        val store = MemoryStore()
        val sent = mutableListOf<Pair<String, String>>()
        var fail: Exception? = null
        val reporter = ActivityReporter(store, { true }, { _, op, body ->
            sent += op to body
            fail?.let { fail = null; throw it }
            when (op) {
                "get-device-activity-checkpoint" -> "{\"sequence\":7,\"epochId\":\"6f1d2c3b-4a5e-4f60-8a7b-9c0d1e2f3a4b\"}"
                else -> { val seq = Json.decodeFromString<ActivityReportV1>(body).sequence; "{\"confirmed\":true,\"sequence\":$seq,\"receivedAt\":\"2026-10-20T18:00:01Z\"}" }
            }
        }, { t0 + 3_600_000 })
        val session = sessionEvent(AppSession("com.google.android.youtube", t0, t0 + 600_000), "YouTube")
        reporter.record(lease, listOf(session, session))
        fail = java.io.IOException("offline")
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { reporter.flush(lease, ActivityStatusNow(true, 2)) } }
        val firstBody = sent.single().second
        reporter.flush(lease, ActivityStatusNow(true, 2))
        assertEquals(firstBody, sent.last().second) // the retry re-sends the exact bytes
        assertEquals(1, Json.decodeFromString<ActivityReportV1>(firstBody).events.size)
        reporter.flush(lease, ActivityStatusNow(true, 2))
        assertEquals(2, sent.size) // nothing new: no upload
        reporter.record(lease, listOf(controlEvent(ControlEvent(ActivityKind.PROTECTION_OFF, t0 + 3_000_000))))
        fail = UsageRequestRejected(409, "STALE_VERSION")
        reporter.flush(lease, ActivityStatusNow(false, 2))
        assertEquals(listOf("report-device-activity", "report-device-activity", "report-device-activity", "get-device-activity-checkpoint", "report-device-activity"), sent.map { it.first })
        assertEquals(8L, Json.decodeFromString<ActivityReportV1>(sent.last().second).sequence)
        assertFalse(Json.decodeFromString<ActivityReportV1>(sent.last().second).status.controlsOn)
    }
}
