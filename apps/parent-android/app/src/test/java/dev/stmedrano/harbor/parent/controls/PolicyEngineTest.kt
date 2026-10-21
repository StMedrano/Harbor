package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.activity.BlockReason
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PolicyEngineTest {
    private val zone = ZoneId.of("America/Chicago")
    // 2026-10-20 is a Tuesday.
    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone)
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val duolingo = "com.duolingo"
    private val exempt = setOf("dev.stmedrano.harbor.parent", "com.android.dialer")
    private val base = ControlsV1(
        blockedApps = listOf(tiktok),
        appLimits = listOf(AppLimit(youtube, 60)),
        alwaysAllowed = listOf(duolingo),
    )
    private fun decide(pkg: String, now: ZonedDateTime, policy: ControlsV1 = base, used: Long = 0) =
        PolicyEngine.decide(pkg, now, policy, used, exempt)
    private fun block(reason: BlockReason) = Decision.Block(reason)

    @Test fun dailyTotalLocksEverythingExceptUnlimitedAndExemptApps() {
        val p = base.copy(dailyLimit = DailyLimit(60))
        val noon = at(20, 12)
        assertEquals(Decision.Allow, PolicyEngine.decide("com.whatsapp", noon, p, 0, exempt, 59 * 60_000L))
        assertEquals(Decision.Block(BlockReason.LIMIT_REACHED, daily = true), PolicyEngine.decide("com.whatsapp", noon, p, 0, exempt, 60 * 60_000L))
        assertEquals(Decision.Allow, PolicyEngine.decide(duolingo, noon, p, 0, exempt, 600 * 60_000L))
        assertEquals(Decision.Allow, PolicyEngine.decide("com.android.dialer", noon, p, 0, exempt, 600 * 60_000L))
        // An outright block still reads as a block, not as time being up.
        assertEquals(block(BlockReason.BLOCKED_APP), PolicyEngine.decide(tiktok, noon, p, 0, exempt, 600 * 60_000L))
        // Extra time counts only on its own day.
        val extra = base.copy(dailyLimit = DailyLimit(60, "2026-10-20", 15))
        assertEquals(Decision.Allow, PolicyEngine.decide("com.whatsapp", noon, extra, 0, exempt, 70 * 60_000L))
        assertEquals(Decision.Block(BlockReason.LIMIT_REACHED, daily = true), PolicyEngine.decide("com.whatsapp", at(21, 12), extra, 0, exempt, 70 * 60_000L))
        assertEquals(5 * 60_000L, PolicyEngine.totalRemainingMs(noon, extra, 70 * 60_000L))
        assertNull(PolicyEngine.totalRemainingMs(noon, base, 70 * 60_000L))
    }

    @Test fun emptyPolicyAllowsEverything() {
        assertEquals(Decision.Allow, PolicyEngine.decide(tiktok, at(20, 12), ControlsV1(), Long.MAX_VALUE, emptySet()))
    }
    @Test fun blockedAppIsBlockedAndOthersAreNot() {
        assertEquals(block(BlockReason.BLOCKED_APP), decide(tiktok, at(20, 12)))
        assertEquals(Decision.Allow, decide("com.example.other", at(20, 12)))
    }
    @Test fun limitBlocksAtExactlyTheAllowanceAndRemainingNeverGoesNegative() {
        assertEquals(Decision.Allow, decide(youtube, at(20, 12), used = 59 * 60_000L + 59_999))
        assertEquals(block(BlockReason.LIMIT_REACHED), decide(youtube, at(20, 12), used = 60 * 60_000L))
        assertEquals(0L, PolicyEngine.remainingMs(youtube, base, 999 * 60_000L))
        assertEquals(60 * 60_000L, PolicyEngine.remainingMs(youtube, base, -5))
        assertNull(PolicyEngine.remainingMs(tiktok, base, 0))
    }
    @Test fun exemptAndAlwaysAllowedAppsSurviveEveryRule() {
        val strict = base.copy(pause = ControlsPause(true, null), bedtime = ControlsBedtime(true, "00:00", "23:59", Weekday.entries))
        for (pkg in exempt + duolingo) assertEquals(pkg, Decision.Allow, decide(pkg, at(20, 12), strict, used = Long.MAX_VALUE))
    }
    @Test fun pauseWithoutEndHoldsAndWithEndExpires() {
        val open = base.copy(pause = ControlsPause(true, null))
        assertEquals(block(BlockReason.PAUSED), decide("com.example.other", at(20, 12), open))
        val timed = base.copy(pause = ControlsPause(true, "2026-10-20T18:00:00Z")) // 13:00 in Chicago
        assertEquals(block(BlockReason.PAUSED), decide("com.example.other", at(20, 12, 59), timed))
        assertEquals(Decision.Allow, decide("com.example.other", at(20, 13, 0), timed))
        assertEquals(Decision.Allow, decide("com.example.other", at(20, 12), base.copy(pause = ControlsPause(false, null))))
    }
    @Test fun pauseOutranksBlockAndLimitInTheReasonShown() {
        val paused = base.copy(pause = ControlsPause(true, null))
        assertEquals(block(BlockReason.PAUSED), decide(tiktok, at(20, 12), paused))
        assertEquals(block(BlockReason.PAUSED), decide(youtube, at(20, 12), paused, used = Long.MAX_VALUE))
    }
    @Test fun bedtimeWithinOneDay() {
        val nap = base.copy(bedtime = ControlsBedtime(true, "13:00", "14:30", listOf(Weekday.TUE)))
        assertEquals(Decision.Allow, decide("com.example.other", at(20, 12, 59), nap))
        assertEquals(block(BlockReason.BEDTIME), decide("com.example.other", at(20, 13, 0), nap))
        assertEquals(block(BlockReason.BEDTIME), decide("com.example.other", at(20, 14, 29), nap))
        assertEquals(Decision.Allow, decide("com.example.other", at(20, 14, 30), nap))
        assertEquals(Decision.Allow, decide("com.example.other", at(21, 13, 30), nap)) // Wednesday not chosen
    }
    @Test fun bedtimeAcrossMidnightBelongsToTheDayItStarts() {
        // School nights: Sunday to Thursday, 21:00 to 07:00.
        val night = base.copy(bedtime = ControlsBedtime(true, "21:00", "07:00",
            listOf(Weekday.SUN, Weekday.MON, Weekday.TUE, Weekday.WED, Weekday.THU)))
        assertEquals(block(BlockReason.BEDTIME), decide("com.example.other", at(20, 21, 0), night))   // Tue night
        assertEquals(block(BlockReason.BEDTIME), decide("com.example.other", at(21, 6, 59), night))   // Wed morning
        assertEquals(Decision.Allow, decide("com.example.other", at(21, 7, 0), night))
        assertEquals(block(BlockReason.BEDTIME), decide("com.example.other", at(23, 6, 0), night))    // Fri morning (Thu night)
        assertEquals(Decision.Allow, decide("com.example.other", at(23, 21, 0), night))                // Fri night not chosen
        assertEquals(Decision.Allow, decide("com.example.other", at(24, 6, 0), night))                 // so Sat morning is free
        assertEquals(block(BlockReason.BEDTIME), decide("com.example.other", at(25, 21, 30), night))  // Sun night
    }
    @Test fun disabledBedtimeNeverBlocks() {
        val off = base.copy(bedtime = ControlsBedtime(false, "00:00", "23:59", emptyList()))
        assertEquals(Decision.Allow, decide("com.example.other", at(20, 12), off))
    }
    @Test fun bedtimeFollowsThePhonesZone() {
        val night = base.copy(bedtime = ControlsBedtime(true, "21:00", "07:00", Weekday.entries))
        val instant = at(20, 22) // 22:00 in Chicago
        assertEquals(block(BlockReason.BEDTIME), decide("com.example.other", instant, night))
        // The same instant on a phone set to Tokyo is 12:00 the next day: not bedtime there.
        assertEquals(Decision.Allow, decide("com.example.other", instant.withZoneSameInstant(ZoneId.of("Asia/Tokyo")), night))
    }
    @Test fun limitsResetAtLocalMidnight() {
        assertEquals(at(20, 23, 59).toLocalDate(), PolicyEngine.limitDay(at(20, 23, 59)))
        assertNotEquals(PolicyEngine.limitDay(at(20, 23, 59)), PolicyEngine.limitDay(at(21, 0, 0)))
    }
}
