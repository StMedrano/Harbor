package dev.stmedrano.harbor.parent.controls

import org.junit.Assert.*
import org.junit.Test

class ControlsModelsTest {
    private val raw = checkNotNull(javaClass.classLoader?.getResource("controls-v1.json")).readText()
    private val controls = decodeControls(raw)
    private fun rejected(value: ControlsV1) = assertThrows(IllegalArgumentException::class.java) { validateControls(value) }

    @Test fun sharedFixtureDecodesAndRoundTrips() {
        assertEquals(listOf("com.zhiliaoapp.musically"), controls.blockedApps)
        assertEquals(AppLimit("com.google.android.youtube", 60), controls.appLimits.single())
        assertEquals(listOf(Weekday.SUN, Weekday.MON, Weekday.TUE, Weekday.WED, Weekday.THU), controls.bedtime?.days)
        assertEquals(controls, decodeControls(encodeControls(controls)))
        assertEquals(ControlsV1(), validateControls(ControlsV1()))
    }
    @Test fun dailyLimitRulesAndLegacyPolicies() {
        assertEquals(DailyLimit(120), controls.dailyLimit)
        validateControls(ControlsV1(dailyLimit = DailyLimit(90, "2026-10-21", 15)))
        rejected(ControlsV1(dailyLimit = DailyLimit(0)))
        rejected(ControlsV1(dailyLimit = DailyLimit(1441)))
        rejected(ControlsV1(dailyLimit = DailyLimit(60, null, 15)))
        rejected(ControlsV1(dailyLimit = DailyLimit(60, "2026-02-30", 15)))
        rejected(ControlsV1(dailyLimit = DailyLimit(60, "2026-10-21", 721)))
        assertEquals(135, DailyLimit(120, "2026-10-21", 15).allowanceMinutes(java.time.LocalDate.of(2026, 10, 21)))
        assertEquals(120, DailyLimit(120, "2026-10-20", 15).allowanceMinutes(java.time.LocalDate.of(2026, 10, 21)))
        // Written before the daily limit existed: no key, read as no daily limit. A wrong inner key is still rejected.
        val legacy = raw.replace(Regex(""",\s*"dailyLimit"[^}]*\}"""), "")
        assertNull(decodeControls(legacy).dailyLimit)
        assertThrows(Exception::class.java) { decodeControls(raw.replace("\"extraMinutes\"", "\"bonus\"")) }
        assertTrue(encodeControls(ControlsV1()).contains("\"dailyLimit\":null"))
    }
    @Test fun unknownKeysAreRejected() {
        assertThrows(Exception::class.java) { decodeControls(raw.replaceFirst("{", "{\"webFilter\":true,")) }
    }
    @Test fun pauseUntilOnlyWhileActive() {
        validateControls(controls.copy(pause = ControlsPause(true, "2026-10-20T20:00:00Z")))
        rejected(controls.copy(pause = ControlsPause(false, "2026-10-20T20:00:00Z")))
        rejected(controls.copy(pause = ControlsPause(true, "2026-02-30T20:00:00Z")))
    }
    @Test fun bedtimeRules() {
        val b = checkNotNull(controls.bedtime)
        assertThrows(IllegalArgumentException::class.java) { validateControls(controls.copy(bedtime = b.copy(start = "24:00"))) }
        rejected(controls.copy(bedtime = b.copy(end = b.start)))
        rejected(controls.copy(bedtime = b.copy(days = listOf(Weekday.MON, Weekday.MON))))
        rejected(controls.copy(bedtime = b.copy(days = emptyList())))
        validateControls(controls.copy(bedtime = b.copy(enabled = false, days = emptyList())))
        assertEquals(21 * 60, clockMinutes("21:00"))
    }
    @Test fun appListsAndOneRulePerApp() {
        rejected(controls.copy(blockedApps = controls.blockedApps + "not a package"))
        rejected(controls.copy(blockedApps = controls.blockedApps + controls.blockedApps))
        rejected(controls.copy(appLimits = listOf(AppLimit("com.google.android.youtube", 0))))
        rejected(controls.copy(appLimits = listOf(AppLimit("com.google.android.youtube", 1441))))
        rejected(controls.copy(alwaysAllowed = controls.alwaysAllowed + controls.blockedApps))
        rejected(controls.copy(blockedApps = controls.blockedApps + "com.google.android.youtube"))
        rejected(controls.copy(alwaysAllowed = controls.alwaysAllowed + "com.google.android.youtube"))
        rejected(controls.copy(alwaysAllowed = (0..50).map { "com.example.app$it" }))
    }
}
