package dev.stmedrano.harbor.parent.activity

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ActivityModelsTest {
    private val now = java.time.Instant.parse("2026-10-20T18:00:30Z").toEpochMilli()
    private val raw = checkNotNull(javaClass.classLoader?.getResource("activity-report-v1.json")).readText()
    private val report = Json.decodeFromString<ActivityReportV1>(raw)
    private fun rejected(value: ActivityReportV1) = assertThrows(IllegalArgumentException::class.java) { validateActivityReport(value, now) }
    private fun withEvent(index: Int, f: (ActivityEvent) -> ActivityEvent) =
        report.copy(events = report.events.mapIndexed { i, e -> if (i == index) f(e) else e })

    @Test fun sharedFixtureRoundTripsAndValidates() {
        assertEquals(report, validateActivityReport(report, now))
        assertEquals(report, Json.decodeFromString<ActivityReportV1>(encodeActivityReport(report)))
        assertEquals(BlockReason.BLOCKED_APP, report.events[2].reason)
    }
    @Test fun noContentFieldExists() {
        val names = ActivityEvent::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "Companion" }.toSet()
        assertEquals(setOf("id", "kind", "at", "endAt", "packageName", "appLabel", "reason"), names)
        assertThrows(Exception::class.java) { Json.decodeFromString<ActivityReportV1>(raw.replace("\"kind\": \"app_session\"", "\"kind\": \"app_session\", \"title\": \"x\"")) }
    }
    @Test fun appEventsCarryAnAppAndOthersDoNot() {
        rejected(withEvent(0) { it.copy(packageName = null) })
        rejected(withEvent(0) { it.copy(appLabel = "") })
        rejected(withEvent(3) { it.copy(packageName = "com.example.app", appLabel = "Example") })
    }
    @Test fun sessionLengthAndReasons() {
        rejected(withEvent(0) { it.copy(endAt = "2026-10-20T16:00:59Z") })
        rejected(withEvent(1) { it.copy(endAt = "2026-10-20T16:26:00Z") })
        rejected(withEvent(2) { it.copy(reason = null) })
        rejected(withEvent(1) { it.copy(reason = BlockReason.BLOCKED_APP) })
    }
    @Test fun orderingUniquenessAndWindow() {
        rejected(report.copy(events = report.events.reversed()))
        rejected(withEvent(1) { it.copy(id = report.events[0].id) })
        rejected(withEvent(3) { it.copy(at = "2026-10-20T18:00:01Z") })
        rejected(report.copy(observedAt = "2026-10-20T18:06:00Z"))
    }
    @Test fun repliesAreStrict() {
        assertEquals(0L, decodeActivityCheckpoint("{\"sequence\":0,\"epochId\":null}").sequence)
        assertThrows(IllegalArgumentException::class.java) { decodeActivityCheckpoint("{\"sequence\":0,\"epochId\":\"${report.epochId}\"}") }
        assertThrows(IllegalStateException::class.java) { decodeActivityWriteReply("{\"confirmed\":true,\"sequence\":2,\"receivedAt\":\"2026-10-20T18:00:01Z\"}", 3) }
        val none = "{\"state\":\"none\",\"controlsOn\":null,\"policyVersion\":null,\"observedAt\":null,\"receivedAt\":null,\"events\":[],\"truncated\":false}"
        assertEquals(ActivityReadState.NONE, decodeActivityRead(none).state)
        assertThrows(IllegalArgumentException::class.java) { decodeActivityRead(none.replace("\"truncated\":false", "\"truncated\":true")) }
    }
}
