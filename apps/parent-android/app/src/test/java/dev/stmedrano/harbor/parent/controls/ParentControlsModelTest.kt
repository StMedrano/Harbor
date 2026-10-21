package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.activity.*
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import dev.stmedrano.harbor.parent.notifications.parentPushText
import dev.stmedrano.harbor.parent.ui.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ParentControlsModelTest {
    private val parentA = ParentIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "session-a")
    private val parentB = ParentIdentity("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "session-b")
    private val device = "22222222-2222-4222-8222-222222222222"
    private val family = "33333333-3333-4333-8333-333333333333"
    private val raw = checkNotNull(javaClass.classLoader?.getResource("controls-v1.json")).readText()

    private class FakeApi(var server: DeviceControlsRead) : ParentControlsApi {
        var deny = false
        var writes = 0
        var raceOnce = false
        var fail = false
        override suspend fun read(deviceId: String): DeviceControlsRead { if (deny) throw FamilyAccessDenied(); return server }
        override suspend fun write(familyId: String, deviceId: String, controls: ControlsV1, expectedVersion: Long): Long {
            writes++
            if (fail) throw IllegalStateException("offline")
            if (raceOnce) { raceOnce = false; server = server.copy(desiredStateVersion = server.desiredStateVersion + 1); throw StaleControls() }
            if (expectedVersion != server.desiredStateVersion) throw StaleControls()
            server = DeviceControlsRead(controls, expectedVersion + 1, server.acknowledgedVersion)
            return expectedVersion + 1
        }
    }

    @Test fun loadShowsWhetherThePhoneHasAppliedTheVersion() = runTest {
        val api = FakeApi(DeviceControlsRead(decodeControls(raw), 4, 3))
        val model = ParentControlsModel(api) { parentA }
        model.load(parentA, device)
        val view = checkNotNull(model.state.value).view
        assertEquals(ControlsViewStatus.AVAILABLE, view.status)
        assertFalse(view.applied)
        api.server = api.server.copy(acknowledgedVersion = 4)
        model.load(parentA, device)
        assertTrue(checkNotNull(model.state.value).view.applied)
    }
    @Test fun nothingSetReadsAsAnEmptyPolicy() = runTest {
        val model = ParentControlsModel(FakeApi(DeviceControlsRead(null, 0, 0))) { parentA }
        model.load(parentA, device)
        assertEquals(ControlsV1(), checkNotNull(model.state.value).view.controls)
    }
    @Test fun saveWritesAtTheKnownVersionAndRetriesOnceAfterARace() = runTest {
        val api = FakeApi(DeviceControlsRead(null, 2, 2))
        val model = ParentControlsModel(api) { parentA }
        model.load(parentA, device)
        val wanted = decodeControls(raw)
        api.raceOnce = true
        assertTrue(model.save(parentA, family, device, wanted))
        assertEquals(2, api.writes)
        assertEquals(wanted, api.server.controls)
        val view = checkNotNull(model.state.value).view
        assertEquals(4L, view.version)
        assertFalse(view.applied)
        assertFalse(model.save(parentA, family, device, wanted.copy(blockedApps = listOf("not a package"))))
    }
    @Test fun eachChangeIsWrittenAtOnceOnTopOfTheLastAndUndoneIfItFails() = runTest {
        val api = FakeApi(DeviceControlsRead(null, 0, 0))
        val model = ParentControlsModel(api) { parentA }
        model.load(parentA, device)
        assertTrue(model.update(parentA, family, device) { withAppRule(it, "com.zhiliaoapp.musically", AppRule.BLOCKED) })
        assertTrue(model.update(parentA, family, device) { it.copy(pause = ControlsPause(true, null)) })
        assertEquals(listOf("com.zhiliaoapp.musically"), api.server.controls?.blockedApps)
        assertTrue(checkNotNull(api.server.controls).pause.active)
        assertEquals(2L, checkNotNull(model.state.value).view.version)
        api.fail = true
        assertFalse(model.update(parentA, family, device) { withDailyMinutes(it, 0) })
        val view = checkNotNull(model.state.value).view
        assertTrue(view.saveFailed)
        assertNull(view.controls.dailyLimit)
        assertFalse(model.update(parentA, family, device) { it.copy(blockedApps = listOf("not a package")) })
    }
    @Test fun dailyLimitStepperExtraTimeAndAppLines() {
        var c = withDailyMinutes(ControlsV1(), 0)
        assertEquals(DailyLimit(120), c.dailyLimit)
        c = withDailyMinutes(c, -15)
        assertEquals(105, c.dailyLimit?.minutes)
        assertEquals(15, withDailyMinutes(ControlsV1(dailyLimit = DailyLimit(15)), -15).dailyLimit?.minutes)
        assertEquals(720, withDailyMinutes(ControlsV1(dailyLimit = DailyLimit(720)), 15).dailyLimit?.minutes)
        val day = LocalDate.of(2026, 10, 21)
        c = withExtraTime(withExtraTime(c, day), day)
        assertEquals(DailyLimit(105, "2026-10-21", 30), c.dailyLimit)
        assertEquals(DailyLimit(105, "2026-10-22", 15), withExtraTime(c, day.plusDays(1)).dailyLimit)
        validateControls(c)
        assertEquals(ControlsV1(), withExtraTime(ControlsV1(), day))
        assertEquals("Blocked" to true, appStatusLine(ControlsV1(blockedApps = listOf("a.b")), "a.b", 0, false))
        assertEquals("40m of 60m today" to false, appStatusLine(ControlsV1(appLimits = listOf(AppLimit("a.b", 60))), "a.b", 40, false))
        assertEquals("60m today · limit reached" to true, appStatusLine(ControlsV1(appLimits = listOf(AppLimit("a.b", 60))), "a.b", 60, false))
        assertEquals("40m today · Unlimited, works after time's up" to false, appStatusLine(ControlsV1(alwaysAllowed = listOf("a.b")), "a.b", 40, false))
        assertEquals("40m today · locked, time's up" to true, appStatusLine(ControlsV1(), "a.b", 40, true))
        assertEquals("1h 05m", formatHm(65))
    }
    @Test fun accessLossAndIdentityChangeHideData() = runTest {
        val api = FakeApi(DeviceControlsRead(decodeControls(raw), 1, 1))
        var who: ParentIdentity? = parentA
        val model = ParentControlsModel(api) { who }
        model.load(parentA, device)
        api.deny = true
        model.load(parentA, device)
        assertEquals(ControlsViewStatus.ACCESS_LOST, checkNotNull(model.state.value).view.status)
        assertEquals(ControlsV1(), checkNotNull(model.state.value).view.controls)
        api.deny = false
        who = parentB
        model.load(parentA, device) // a stale identity's request is dropped
        assertEquals(ControlsViewStatus.ACCESS_LOST, checkNotNull(model.state.value).view.status)
        model.clear()
        assertNull(model.state.value)
    }
    @Test fun appRulesAreExclusive() {
        var c = ControlsV1()
        c = withAppRule(c, "com.google.android.youtube", AppRule.BLOCKED)
        c = withAppRule(c, "com.google.android.youtube", AppRule.LIMITED, 45)
        assertEquals(AppRule.LIMITED, ruleOf(c, "com.google.android.youtube"))
        assertTrue(c.blockedApps.isEmpty())
        c = withAppRule(c, "com.google.android.youtube", AppRule.ALWAYS)
        assertTrue(c.appLimits.isEmpty())
        validateControls(c)
        assertEquals(AppRule.ALLOWED, ruleOf(withAppRule(c, "com.google.android.youtube", AppRule.ALLOWED), "com.google.android.youtube"))
    }
    @Test fun activityTotalsLinesAndDayWindow() {
        val raw = checkNotNull(javaClass.classLoader?.getResource("activity-report-v1.json")).readText()
        val events = kotlinx.serialization.json.Json.decodeFromString<ActivityReportV1>(raw).events
        val totals = appTotals(events, java.time.Instant.parse("2026-10-20T16:10:00Z").toEpochMilli(), java.time.Instant.parse("2026-10-21T00:00:00Z").toEpochMilli())
        assertEquals(listOf(AppTotal("com.google.android.youtube", "YouTube", 15 * 60_000L)), totals) // clipped at the window start
        assertEquals("YouTube · 25 min", activityLine(events[0]))
        assertEquals("YouTube daily time limit reached", activityLine(events[1]))
        assertEquals("Tried to open TikTok (blocked)", activityLine(events[2]))
        assertEquals("Bedtime started", activityLine(events[3]))
        val (from, to) = dayWindow(LocalDate.of(2026, 11, 1), ZoneId.of("America/Chicago")) // DST ends: a 25-hour day
        assertEquals("2026-11-01T05:00:00Z", from)
        assertEquals("2026-11-02T06:00:00Z", to)
    }
    @Test fun controlsOffPushIsAllowedAndGeneric() {
        assertEquals("App controls were turned off on a child's phone. Open Harbor to check.", parentPushText("device.controls.off"))
        assertFalse(parentPushText("device.controls.off").contains("@"))
    }
}
