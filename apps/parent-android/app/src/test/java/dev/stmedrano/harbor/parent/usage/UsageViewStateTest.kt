package dev.stmedrano.harbor.parent.usage

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsageViewStateTest {
    private val now = java.time.Instant.parse("2026-10-08T12:20:00Z").toEpochMilli()
    private val base = Json.decodeFromString<UsageReportV1>(checkNotNull(javaClass.classLoader?.getResource("usage-report-v1.json")).readText())
    private fun reply(report: UsageReportV1 = base, received: String = "2026-10-08T12:10:00Z") = UsageReadReplyV1(UsageReadState.AVAILABLE, report, received)

    @Test fun durationsKeepUnknownDistinctFromZero() {
        assertEquals("0m", formatUsageDuration(0)); assertEquals("1h 05m", formatUsageDuration(65 * 60000L))
        assertEquals("0m", formatUsageDuration(59_999)); assertEquals("Unknown", formatUsageDuration(null))
    }

    @Test fun replyStatesAreDistinct() {
        assertEquals(UsageViewStatus.UNAVAILABLE, UsageViewState.fromReply(null, now).status)
        assertEquals(UsageViewStatus.NO_REPORT, UsageViewState.fromReply(UsageReadReplyV1(UsageReadState.NONE, null, null), now).status)
        assertEquals(UsageViewStatus.EXPIRED, UsageViewState.fromReply(UsageReadReplyV1(UsageReadState.EXPIRED, null, null), now).status)
        assertEquals(UsageViewStatus.UNAVAILABLE, UsageViewState.fromReply(UsageReadReplyV1(UsageReadState.AVAILABLE, null, null), now).status)
    }

    @Test fun measuredReplyKeepsTotalAndNeverSumsOverlappingApps() {
        val day = base.days.single().copy(totalMs = 5_400_000, apps = listOf(UsageApp("example.test", 4_200_000), UsageApp("other.app", 3_000_000)))
        val view = UsageViewState.fromReply(reply(base.copy(days = listOf(day))), now)
        assertEquals(UsageViewStatus.MEASURED, view.status)
        assertEquals(5_400_000L, view.today?.totalMs)
        assertEquals(listOf("example.test", "other.app"), view.apps.map { it.packageName })
        assertNull(view.apps.first { it.packageName == "other.app" }.label)
    }

    @Test fun deniedPermissionMakesNoUsageClaim() {
        val denied = base.copy(usagePermission = UsagePermissionState.DENIED, inventoryStatus = InventoryStatus.UNAVAILABLE, inventory = emptyList(), days = emptyList())
        val view = UsageViewState.fromReply(reply(denied), now)
        assertEquals(UsageViewStatus.PERMISSION_REQUIRED, view.status)
        assertTrue(view.days.isEmpty()); assertTrue(view.apps.isEmpty())
        assertTrue(usageNotices(view).single().contains("unknown, not zero"))
    }

    @Test fun staleIsOlderThanThirtyMinutesOfServerReceipt() {
        assertFalse(UsageViewState.fromReply(reply(received = "2026-10-08T11:50:00Z"), now).stale)
        assertTrue(UsageViewState.fromReply(reply(received = "2026-10-08T11:49:59Z"), now).stale)
        assertTrue(usageNotices(UsageViewState.fromReply(reply(received = "2026-10-08T11:00:00Z"), now)).any { it.startsWith("Out of date") })
    }

    @Test fun unknownTotalStaysNullAndPartialDayIsExplained() {
        val partial = base.days.single().copy(quality = UsageQuality.PARTIAL, coverageStart = "2026-10-08T09:30:00.000Z", totalMs = 60000, apps = emptyList())
        val view = UsageViewState.fromReply(reply(base.copy(days = listOf(partial))), now)
        assertTrue(usageNotices(view).any { it.contains("Partial day") && it.contains("09:30") })
        val unknown = base.days.single().copy(quality = UsageQuality.UNAVAILABLE, coverageStart = null, totalMs = null, apps = emptyList())
        val none = UsageViewState.fromReply(reply(base.copy(days = listOf(unknown))), now)
        assertNull(none.today?.totalMs); assertTrue(usageNotices(none).contains("Today was not measured."))
    }

    @Test fun offlineParentReadKeepsLastReportAndSaysSo() {
        val view = UsageViewState.fromReply(reply(), now, offline = true)
        assertEquals(UsageViewStatus.MEASURED, view.status)
        assertTrue(usageNotices(view).any { it.startsWith("Can't reach Harbor") })
    }

    @Test fun childWithoutConsentOrPermissionShowsNoMeasurement() {
        val off = UsageViewState.fromLocal(null, UsageRuntimeStatus.DISABLED, true, now) { null }
        assertEquals(UsageViewStatus.SHARING_OFF, off.status)
        val state = UsageStoredState("22222222-2222-4222-8222-222222222222", "11111111-1111-4111-8111-111111111111", consent = true)
        assertEquals(UsageViewStatus.PERMISSION_REQUIRED, UsageViewState.fromLocal(state, UsageRuntimeStatus.PERMISSION_DENIED, false, now) { null }.status)
        val collecting = UsageViewState.fromLocal(state, UsageRuntimeStatus.READY, true, now) { null }
        assertEquals(UsageViewStatus.NO_REPORT, collecting.status); assertTrue(collecting.collecting)
    }

    @Test fun childPendingUploadAndOfflineAreVisibleAndLabelsAreLocal() {
        val aggregate = UsageReduction(base.days, UsageCoverage(emptyList()), UsageWindow(0, 1, "UTC"))
        val pending = PendingReport(base, "{}", "hash")
        val state = UsageStoredState("22222222-2222-4222-8222-222222222222", base.epochId, consent = true, latestAggregate = aggregate, pending = pending)
        val view = UsageViewState.fromLocal(state, UsageRuntimeStatus.OFFLINE, true, now) { if (it == "example.test") "Local label" else null }
        assertEquals(UsageViewStatus.MEASURED, view.status)
        assertTrue(view.offline); assertTrue(view.uploadPending); assertNull(view.receivedAt)
        assertEquals("Local label", view.apps.single().label)
        assertTrue(usageNotices(view).any { it.startsWith("Offline") })
    }

    @Test fun noNoticeEverClaimsEnforcement() {
        val all = listOf(UsageViewStatus.values().map { UsageViewState(it, UsageViewOrigin.PARENT_READ, stale = true, offline = true) }).flatten()
            .flatMap(::usageNotices).joinToString(" ").lowercase()
        listOf("block", "limit", "locked", "restrict").forEach { assertFalse(all.contains(it)) }
    }
}
