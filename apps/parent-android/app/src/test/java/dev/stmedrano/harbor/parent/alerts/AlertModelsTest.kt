package dev.stmedrano.harbor.parent.alerts

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AlertModelsTest {
    private val now = java.time.Instant.parse("2026-10-10T18:00:00Z").toEpochMilli()
    private val raw = checkNotNull(javaClass.classLoader?.getResource("alert-report-v1.json")).readText()
    private val report = Json.decodeFromString<AlertReportV1>(raw)
    private fun rejected(value: AlertReportV1) = assertThrows(IllegalArgumentException::class.java) { validateAlertReport(value, now) }

    @Test fun sharedFixtureRoundTripsAndValidates() {
        assertEquals(report, validateAlertReport(report, now))
        assertEquals(report, Json.decodeFromString<AlertReportV1>(Json.encodeToString(AlertReportV1.serializer(), report)))
        assertEquals(AlertCategory.BULLYING, report.alerts[0].category)
        assertEquals(AlertCategory.UNKNOWN_ADULT_CONTACT, report.alerts[1].category)
        assertTrue(report.notificationAccess)
        assertFalse(report.smsPermission)
    }
    @Test fun noTextSenderOrExcerptFieldExists() {
        val names = MessageAlert::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "Companion" }.toSet()
        assertEquals(setOf("id", "appLabel", "category", "ruleSetVersion", "at", "savedContact"), names)
        val reportNames = AlertReportV1::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") || it == "Companion" }.toSet()
        assertEquals(setOf("version", "epochId", "sequence", "observedAt", "notificationAccess", "smsPermission", "alerts"), reportNames)
    }
    @Test fun everyCategoryHasTheContractWireName() {
        val wire = mapOf(AlertCategory.BULLYING to "bullying", AlertCategory.SELF_HARM to "self_harm", AlertCategory.ADULT_CONTENT to "adult_content",
            AlertCategory.DRUGS_ALCOHOL to "drugs_alcohol", AlertCategory.VIOLENCE_THREAT to "violence_threat",
            AlertCategory.UNKNOWN_ADULT_CONTACT to "unknown_adult_contact")
        assertEquals(AlertCategory.entries.toSet(), wire.keys)
        wire.forEach { (category, name) -> assertEquals("\"$name\"", Json.encodeToString(AlertCategory.serializer(), category)) }
    }
    @Test fun fieldsAreBounded() {
        val a = report.alerts[0]
        for (bad in listOf(a.copy(id = "nope"), a.copy(appLabel = ""), a.copy(appLabel = "x".repeat(65)), a.copy(appLabel = "bad\u0000"),
            a.copy(appLabel = "line\nbreak"), a.copy(ruleSetVersion = 0), a.copy(at = "2026-10-10 17:41:00"), a.copy(at = "2026-02-30T00:00:00Z")))
            rejected(report.copy(alerts = listOf(bad)))
        validateAlertReport(report.copy(alerts = listOf(a.copy(appLabel = "x".repeat(64)))), now)
    }
    @Test fun timeOrderingDuplicatesAndCountAreEnforced() {
        rejected(report.copy(observedAt = "2026-10-10T18:05:00.001Z"))
        rejected(report.copy(alerts = listOf(report.alerts[0], report.alerts[1].copy(at = "2026-10-10T18:00:01Z"))))
        rejected(report.copy(alerts = listOf(report.alerts[0].copy(at = "2026-09-10T17:59:59Z"))))
        rejected(report.copy(alerts = report.alerts.reversed()))
        rejected(report.copy(alerts = listOf(report.alerts[0], report.alerts[1].copy(id = report.alerts[0].id.uppercase()))))
        rejected(report.copy(sequence = 0))
        rejected(report.copy(version = 2))
        rejected(report.copy(epochId = "not-a-uuid"))
        validateAlertReport(report.copy(alerts = listOf(report.alerts[0], report.alerts[1].copy(at = report.alerts[0].at))), now)
        fun alertAt(i: Int) = report.alerts[0].copy(id = "00000000-0000-4000-8000-%012d".format(i), at = alertInstantString(now - (60L - i) * 60_000))
        validateAlertReport(report.copy(alerts = (0 until 50).map(::alertAt)), now)
        rejected(report.copy(alerts = (0 until 51).map(::alertAt)))
    }
    @Test fun instantsUseSecondResolution() {
        assertEquals("2026-10-10T17:50:00Z", alertInstantString(java.time.Instant.parse("2026-10-10T17:50:00.987Z").toEpochMilli()))
        assertEquals("1969-12-31T23:59:59Z", alertInstantString(-1))
    }
    @Test fun checkpointAndWriteRepliesAreStrict() {
        assertEquals(AlertCheckpointReplyV1(0, null), decodeAlertCheckpoint("{\"sequence\":0,\"epochId\":null}"))
        assertThrows(IllegalArgumentException::class.java) { decodeAlertCheckpoint("{\"sequence\":3,\"epochId\":null}") }
        assertThrows(IllegalArgumentException::class.java) { decodeAlertCheckpoint("{\"sequence\":0,\"epochId\":\"${report.epochId}\"}") }
        assertEquals(3L, decodeAlertWriteReply("{\"confirmed\":true,\"sequence\":3,\"receivedAt\":\"2026-10-10T18:00:01Z\"}", 3).sequence)
        assertThrows(IllegalStateException::class.java) { decodeAlertWriteReply("{\"confirmed\":true,\"sequence\":4,\"receivedAt\":\"2026-10-10T18:00:01Z\"}", 3) }
        assertThrows(IllegalStateException::class.java) { decodeAlertWriteReply("{\"confirmed\":false,\"sequence\":3,\"receivedAt\":\"2026-10-10T18:00:01Z\"}", 3) }
    }
    @Test fun readReplyIsStrictAndNewestFirst() {
        val newestFirst = report.alerts.reversed()
        val available = AlertReadReplyV1(AlertReadState.AVAILABLE, true, false, "2026-10-10T18:00:00Z", "2026-10-10T18:00:02Z", newestFirst, "2026-10-10T17:41:00Z")
        assertEquals(available, decodeAlertRead(Json.encodeToString(AlertReadReplyV1.serializer(), available)))
        val none = AlertReadReplyV1(AlertReadState.NONE, null, null, null, null, emptyList(), null)
        assertEquals(none, decodeAlertRead(Json.encodeToString(AlertReadReplyV1.serializer(), none)))
        fun bad(r: AlertReadReplyV1) = assertThrows(IllegalArgumentException::class.java) { decodeAlertRead(Json.encodeToString(AlertReadReplyV1.serializer(), r)) }
        bad(available.copy(alerts = report.alerts))
        bad(available.copy(alerts = emptyList()))
        bad(none.copy(alerts = newestFirst))
        bad(none.copy(state = AlertReadState.EXPIRED, notificationAccess = true))
        bad(none.copy(nextBefore = "2026-10-10T17:41:00Z"))
        assertThrows(Exception::class.java) { decodeAlertRead(Json.encodeToString(AlertReadReplyV1.serializer(), available).dropLast(1) + ",\"text\":\"x\"}") }
    }
}
