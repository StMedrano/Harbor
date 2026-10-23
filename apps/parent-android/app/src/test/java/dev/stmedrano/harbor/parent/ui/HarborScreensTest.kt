package dev.stmedrano.harbor.parent.ui

import dev.stmedrano.harbor.parent.activity.ActivityEvent
import dev.stmedrano.harbor.parent.activity.ActivityKind
import dev.stmedrano.harbor.parent.activity.ActivityViewState
import dev.stmedrano.harbor.parent.activity.ActivityViewStatus
import dev.stmedrano.harbor.parent.activity.AppTotal
import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.activity.ScreenTime
import dev.stmedrano.harbor.parent.checkin.CheckInEntry
import dev.stmedrano.harbor.parent.checkin.CheckInStatus
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Screen logic of the design-system rebuild that does not need a phone. Synthetic data only. */
class HarborScreensTest {
    private val day = LocalDate.of(2026, 10, 23)
    private val base = Instant.parse("2026-10-23T15:00:00Z")
    private fun at(minutes: Long) = base.plusSeconds(minutes * 60).toString()

    @Test fun anOpenCheckInReadsNoResponseAfterTenMinutes() {
        val entry = CheckInEntry("r", at(0), at(60 * 24), CheckInStatus.PENDING, null)
        val asked = base.toEpochMilli()
        assertFalse(checkInWaitingTooLong(entry, asked + 9 * 60_000L + 59_000L))
        assertTrue(checkInWaitingTooLong(entry, asked + CHECK_IN_NO_RESPONSE_MS))
    }

    @Test fun theDaysFigurePrefersThePhonesOwnCountThenAppTimeThenADash() {
        val totals = listOf(AppTotal("a.b", "YouTube", 42 * 60_000L), AppTotal("c.d", "Chat", 25 * 60_000L))
        val phone = ScreenTime(day.toString(), 80 * 60_000L, emptyList())
        assertEquals("1h 20m" to "Screen time", activityDayFigure(ActivityViewState(ActivityViewStatus.AVAILABLE, day, totals = totals, screenTime = phone)))
        // Another day's count is not this day's: fall back to the timeline's app time.
        assertEquals("1h 07m" to "Time in apps",
            activityDayFigure(ActivityViewState(ActivityViewStatus.AVAILABLE, day, totals = totals, screenTime = phone.copy(date = "2026-10-22"))))
        assertEquals(null to "Time in apps", activityDayFigure(ActivityViewState(ActivityViewStatus.AVAILABLE, day)))
    }

    @Test fun theTimelineIsNewestFirstWithRulesInAmber() {
        val events = listOf(
            ActivityEvent("1", ActivityKind.APP_SESSION, at(0), at(42), "a.b", "YouTube", null),
            ActivityEvent("2", ActivityKind.BLOCKED_OPEN, at(60), null, "c.d", "TikTok", BlockReason.BLOCKED_APP),
            ActivityEvent("3", ActivityKind.BEDTIME_STARTED, at(120), null, null, null, null),
        )
        val items = activityTimeline(events, ZoneOffset.UTC)
        assertEquals(listOf("Bedtime started", "Tried to open TikTok (blocked)", "YouTube · 42 min"), items.map { it.text })
        assertEquals(listOf(true, true, false), items.map { it.review })
        assertEquals("5:00 PM", items.first().time)
    }

    @Test fun theTimelineShowsTheNewestFiveUntilShowMore() {
        val events = (0L until 8L).map { ActivityEvent("e$it", ActivityKind.APP_SESSION, at(it * 10), at(it * 10 + 5), "a.b", "YouTube", null) }
        val items = activityTimeline(events, ZoneOffset.UTC)
        assertEquals(items.take(5), timelinePreview(items, expanded = false))
        assertEquals("4:10 PM", timelinePreview(items, expanded = false).first().time) // newest first
        assertEquals(items, timelinePreview(items, expanded = true))
        assertEquals(items.take(3), timelinePreview(items.take(3), expanded = false))
    }

    @Test fun everyChildTabHasAnEyebrowAndOnlyTodayGreets() {
        assertEquals("Today" to null, childHeader("Today"))
        assertEquals("Your parents asked" to "Share my check-in", childHeader(CHECK_IN_TAB))
        assertEquals("What your parents see" to "About Harbor on your phone", childHeader("About"))
        listOf("Apps", FAMILY_MAP_TAB).forEach { assertNotNull(childHeader(it).second) }
    }
}
