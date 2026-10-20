package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.ui.ChildControlsUi
import dev.stmedrano.harbor.parent.ui.controlsSummary
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ControlsSummaryTest {
    private val noon = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneId.of("America/Chicago"))
    private fun ui(c: ControlsV1?, remaining: Map<String, Long> = emptyMap(), at: ZonedDateTime = noon) =
        ChildControlsUi(c, true, mapOf("com.zhiliaoapp.musically" to "TikTok", "com.google.android.youtube" to "YouTube"), remaining, at) {}

    @Test fun noRulesMeansNoLines() {
        assertTrue(controlsSummary(ui(null)).isEmpty())
        assertTrue(controlsSummary(ui(ControlsV1())).isEmpty())
    }
    @Test fun eachRuleIsOnePlainLine() {
        val c = ControlsV1(
            bedtime = ControlsBedtime(true, "21:00", "07:00", Weekday.entries),
            blockedApps = listOf("com.zhiliaoapp.musically"),
            appLimits = listOf(AppLimit("com.google.android.youtube", 60)),
        )
        assertEquals(listOf(
            "Bedtime is 21:00 to 07:00 every night.",
            "Blocked: TikTok.",
            "YouTube: 60 min a day — 12 min left today",
        ), controlsSummary(ui(c, mapOf("com.google.android.youtube" to 12 * 60_000L + 30_000L))))
        assertEquals("YouTube: 60 min a day — used up for today", controlsSummary(ui(c, mapOf("com.google.android.youtube" to 0L))).last())
        assertTrue(controlsSummary(ui(c, at = noon.withHour(22))).first().endsWith("It's bedtime now."))
    }
    @Test fun pauseAndChosenDaysAreSpelledOut() {
        val c = ControlsV1(pause = ControlsPause(true, null), bedtime = ControlsBedtime(true, "21:00", "07:00", listOf(Weekday.SUN, Weekday.MON)))
        val lines = controlsSummary(ui(c))
        assertEquals("Your phone is paused by a parent. Calls and texts still work.", lines[0])
        assertEquals("Bedtime is 21:00 to 07:00 on Sun, Mon.", lines[1])
    }
}
