package dev.stmedrano.harbor.parent.usage

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsageContractTest {
    private val now = java.time.Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private val raw get() = javaClass.getResource("/usage-report-v1.json")!!.readText()
    private fun report() = decodeUsageReport(raw, now)
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected invalid usage contract") } catch (_: IllegalArgumentException) { }
    }
    @Test fun crossLanguageRoundTrip() {
        val r = report()
        assertEquals(1, r.version); assertEquals(60000L, r.days.single().totalMs)
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(Json.encodeToString(r)))
        rejects { decodeUsageReport(raw.trim().dropLast(1)+",\"familyId\":\"foreign\"}", now) }
    }
    @Test fun preservesUnknownDurations() {
        val r=report(); val d=r.days.single().copy(quality=UsageQuality.UNAVAILABLE,totalMs=null,coverageStart=null,apps=emptyList())
        assertNull(validateUsageReport(r.copy(days=listOf(d)),now).days.single().totalMs)
        rejects { validateUsageReport(r.copy(days=listOf(d.copy(totalMs=0))),now) }
    }
    @Test fun boundsDailyElapsedAndDuplicateRows() {
        val r=report(); val d=r.days.single()
        rejects { validateUsageReport(r.copy(days=listOf(d.copy(totalMs=43200001))),now) }
        rejects { validateUsageReport(r.copy(days=listOf(d.copy(apps=listOf(d.apps[0].copy(foregroundMs=-1))))),now) }
        rejects { validateUsageReport(r.copy(days=listOf(d,d)),now) }
        rejects { validateUsageReport(r.copy(days=listOf(d.copy(apps=d.apps+d.apps))),now) }
        rejects { validateUsageReport(r.copy(inventory=r.inventory+r.inventory),now) }
        rejects { validateUsageReport(r.copy(days=listOf(d.copy(startAt="2026-10-08T01:00:00Z"))),now) }
        rejects { validateUsageReport(r.copy(zoneId="Fake/Zone"),now) }
    }
    @Test fun boundsBytesPackagesAndUnicodeLabels() {
        val base=raw.trim(); val exact=base+" ".repeat(1048576-base.toByteArray(Charsets.UTF_8).size)
        assertEquals(report(),decodeUsageReport(exact,now))
        rejects { decodeUsageReport(exact+" ",now) }
        val r=report()
        rejects { validateUsageReport(r.copy(inventory=List(501){UsageInventoryApp("example.app$it","App")}),now) }
        rejects { validateUsageReport(r.copy(days=listOf(r.days[0].copy(apps=List(3501){UsageApp("example.app$it",1)}))),now) }
        validateUsageReport(r.copy(inventoryStatus=InventoryStatus.TRUNCATED),now)
        validateUsageReport(r.copy(inventory=listOf(UsageInventoryApp("example.test","😀".repeat(200)))),now)
        rejects { validateUsageReport(r.copy(inventory=listOf(UsageInventoryApp("example.test","😀".repeat(201)))),now) }
        rejects { validateUsageReport(r.copy(inventory=listOf(UsageInventoryApp("example.test","bad\u0000label"))),now) }
    }
    @Test fun windowQualityAndCheckpointTypes() {
        val r=report(); val d=r.days.single()
        rejects { validateUsageReport(r.copy(days=listOf(d.copy(localDate="2026-10-01"))),now) }
        rejects { validateUsageReport(r.copy(days=listOf(d.copy(coverageStart=null))),now) }
        rejects { validateUsageReport(r.copy(observedAt="2026-10-08T12:05:00.001Z"),now) }
        assertEquals(0L,decodeUsageCheckpoint("{\"sequence\":0,\"epochId\":null}").sequence)
        rejects { decodeUsageCheckpoint("{\"sequence\":1,\"epochId\":null}") }
    }
    @Test fun clearAndCheckpointRequestsRejectAuthorityAndInvalidSequence() {
        assertEquals(2L, decodeClearUsage("{\"version\":1,\"epochId\":\"11111111-1111-4111-8111-111111111111\",\"sequence\":2}").sequence)
        rejects { decodeClearUsage("{\"version\":1,\"epochId\":\"11111111-1111-4111-8111-111111111111\",\"sequence\":0}") }
        rejects { decodeClearUsage("{\"version\":1,\"epochId\":\"invalid\",\"sequence\":1}") }
        assertEquals(1,decodeUsageCheckpointRequest("{\"version\":1}").version)
        rejects { decodeUsageCheckpointRequest("{\"version\":2}") }
        rejects { decodeUsageCheckpointRequest("{\"version\":1,\"deviceId\":\"foreign\"}") }
    }    @Test fun rejectsUnicodeControlLabels() { rejects { validateUsageReport(report().copy(inventory=listOf(UsageInventoryApp("example.test","bad\u0085label"))),now) } }
}
