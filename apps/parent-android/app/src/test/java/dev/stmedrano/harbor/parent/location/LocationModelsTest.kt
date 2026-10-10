package dev.stmedrano.harbor.parent.location

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocationModelsTest {
    private val now = java.time.Instant.parse("2026-10-10T18:00:00Z").toEpochMilli()
    private val raw = checkNotNull(javaClass.classLoader?.getResource("location-report-v1.json")).readText()
    private val report = Json.decodeFromString<LocationReportV1>(raw)
    private fun rejected(value: LocationReportV1) = assertThrows(IllegalArgumentException::class.java) { validateLocationReport(value, now) }

    @Test fun sharedFixtureRoundTripsAndValidates() {
        assertEquals(report, validateLocationReport(report, now))
        assertEquals(report, Json.decodeFromString<LocationReportV1>(Json.encodeToString(LocationReportV1.serializer(), report)))
        assertEquals(LocationStatus.SHARING, report.status)
        assertEquals(LocationProvider.NETWORK, report.fixes[1].provider)
    }
    @Test fun coordinatesAndAccuracyAreBounded() {
        for (bad in listOf(report.fixes[0].copy(latitude = 90.000001), report.fixes[0].copy(latitude = -91.0), report.fixes[0].copy(longitude = 180.5),
            report.fixes[0].copy(longitude = Double.NaN), report.fixes[0].copy(accuracyM = -1), report.fixes[0].copy(accuracyM = 100_001),
            report.fixes[0].copy(latitude = 41.8781141))) rejected(report.copy(fixes = listOf(bad)))
        validateLocationReport(report.copy(fixes = listOf(report.fixes[0].copy(latitude = 90.0, longitude = -180.0, accuracyM = 0))), now)
    }
    @Test fun timeOrderingAndWindowAreEnforced() {
        rejected(report.copy(observedAt = "2026-10-10T18:05:00.001Z"))
        rejected(report.copy(fixes = listOf(report.fixes[0], report.fixes[1].copy(at = "2026-10-10T18:00:01Z"))))
        rejected(report.copy(fixes = listOf(report.fixes[0].copy(at = "2026-09-10T17:59:59Z"))))
        rejected(report.copy(fixes = listOf(report.fixes[0], report.fixes[0])))
        rejected(report.copy(fixes = report.fixes.reversed()))
        rejected(report.copy(sequence = 0))
        rejected(report.copy(version = 2))
        rejected(report.copy(epochId = "not-a-uuid"))
    }
    @Test fun batchIsCappedAtOneHundred() {
        fun fixAt(i: Int) = report.fixes[0].copy(at = locationInstantString(now - (100L - i) * 60_000))
        validateLocationReport(report.copy(fixes = (0 until 100).map(::fixAt)), now)
        rejected(report.copy(fixes = (0 until 101).map { report.fixes[0].copy(at = locationInstantString(now - (101L - it) * 60_000)) }))
    }
    @Test fun instantsUseSecondResolutionAndFloorNegativeTimes() {
        assertEquals("2026-10-10T17:50:00Z", locationInstantString(java.time.Instant.parse("2026-10-10T17:50:00.987Z").toEpochMilli()))
        assertEquals("1969-12-31T23:59:59Z", locationInstantString(-1))
    }
    @Test fun checkpointAndWriteRepliesAreStrict() {
        assertEquals(LocationCheckpointReplyV1(0, null), decodeLocationCheckpoint("{\"sequence\":0,\"epochId\":null}"))
        assertThrows(IllegalArgumentException::class.java) { decodeLocationCheckpoint("{\"sequence\":3,\"epochId\":null}") }
        assertThrows(IllegalArgumentException::class.java) { decodeLocationCheckpoint("{\"sequence\":0,\"epochId\":\"${report.epochId}\"}") }
        assertEquals(7L, decodeLocationWriteReply("{\"confirmed\":true,\"sequence\":7,\"receivedAt\":\"2026-10-10T18:00:01Z\"}", 7).sequence)
        assertThrows(IllegalStateException::class.java) { decodeLocationWriteReply("{\"confirmed\":true,\"sequence\":8,\"receivedAt\":\"2026-10-10T18:00:01Z\"}", 7) }
        assertThrows(IllegalStateException::class.java) { decodeLocationWriteReply("{\"confirmed\":false,\"sequence\":7,\"receivedAt\":\"2026-10-10T18:00:01Z\"}", 7) }
    }
}
