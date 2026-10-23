package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.access.AccessReason
import dev.stmedrano.harbor.parent.access.AskResult
import dev.stmedrano.harbor.parent.access.ChildAccessAsker
import dev.stmedrano.harbor.parent.activity.ActivityReadState
import dev.stmedrano.harbor.parent.activity.AppTime
import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.activity.ScreenTime
import dev.stmedrano.harbor.parent.activity.decodeActivityRead
import dev.stmedrano.harbor.parent.activity.validateScreenTime
import dev.stmedrano.harbor.parent.ui.AppRule
import dev.stmedrano.harbor.parent.ui.withAppRule
import dev.stmedrano.harbor.parent.ui.withPauseNow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SyncAndGrantsTest {
    private val yt = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val home = "com.google.android.apps.nexuslauncher"
    private val settings = "com.android.settings"
    private val noon = ZonedDateTime.of(2026, 10, 22, 12, 0, 0, 0, ZoneId.of("America/Chicago"))
    private fun m(min: Long, pkg: String?, kind: MarkKind) = UsageMark(min * 60_000L, pkg, kind)

    @Test fun aLaterPauseOrNewRuleEndsAnEarlierYes() {
        val withYes = ControlsV1(grants = listOf(Grant(yt, "2026-10-22T18:15:00Z"), Grant(tiktok, "2026-10-22T18:15:00Z")))
        val paused = withPauseNow(withYes)
        assertTrue(paused.pause.active)
        assertTrue(paused.grants.isEmpty())
        val blocked = withAppRule(withYes, yt, AppRule.BLOCKED)
        assertEquals(listOf(yt), blocked.blockedApps)
        assertEquals(listOf(tiktok), blocked.grants.map { it.packageName }) // only that app's yes ends
        validateControls(blocked)
    }

    @Test fun aRaisedOrRemovedLimitLiftsTheBlockScreen() {
        val usage = VisibleUsage.of(listOf(m(0, yt, MarkKind.RESUMED), m(20, yt, MarkKind.PAUSED), m(20, yt, MarkKind.STOPPED)), 0, 30 * 60_000L)
        val limited = ControlsV1(appLimits = listOf(AppLimit(yt, 15)))
        assertFalse(blockLifted(yt, BlockReason.LIMIT_REACHED, limited, noon, usage))
        assertTrue(blockLifted(yt, BlockReason.LIMIT_REACHED, limited.copy(appLimits = listOf(AppLimit(yt, 30))), noon, usage))
        assertTrue(blockLifted(yt, BlockReason.LIMIT_REACHED, ControlsV1(), noon, usage))
        assertFalse(blockLifted(yt, BlockReason.LIMIT_REACHED, limited, noon, null)) // without today's time it stays closed
        val daily = ControlsV1(dailyLimit = DailyLimit(15))
        assertFalse(blockLifted(yt, BlockReason.LIMIT_REACHED, daily, noon, usage))
        assertTrue(blockLifted(yt, BlockReason.LIMIT_REACHED, daily.copy(dailyLimit = DailyLimit(15, "2026-10-22", 15)), noon, usage))
    }

    // 0.27 field test: removing the limit showed "Your parent said yes" and waited for a tap instead of lifting.
    @Test fun aRuleChangeOpensTheAppByItselfOnceAMinuteAndAYesIsAnnounced() {
        val now = 1_000_000L
        assertEquals(BlockLift.NONE, blockLift(false, false, null, now))
        assertEquals(BlockLift.ANNOUNCE_YES, blockLift(true, true, null, now))
        assertEquals(BlockLift.OPEN, blockLift(true, false, null, now))
        assertEquals(BlockLift.OFFER_OPEN, blockLift(true, false, now - 59_000L, now))
        assertEquals(BlockLift.OPEN, blockLift(true, false, now - 60_000L, now))
    }

    @Test fun theChildCanAskAgainOnceARequestIsAnswered() = runTest {
        var now = 0L
        val asker = ChildAccessAsker({ """{"requestId":"8a1f3c2e-5b7d-4e9a-9c1b-2d3e4f5a6b7c","status":"pending","requestedAt":"2026-10-22T15:00:00Z"}""" }) { now }
        assertEquals(AskResult.SENT, asker.ask(AccessReason.BLOCKED_APP, yt, "YouTube"))
        assertTrue(asker.askedRecently(yt))
        asker.forget(yt)
        assertFalse(asker.askedRecently(yt))
        asker.ask(AccessReason.BLOCKED_APP, yt, "YouTube"); asker.ask(AccessReason.PAUSED, tiktok, "TikTok")
        asker.clear()
        assertFalse(asker.askedRecently(yt) || asker.askedRecently(tiktok))
    }

    @Test fun screenTimeCountsLikeDigitalWellbeingAndSendsWholeMinutes() {
        val marks = listOf(m(0, yt, MarkKind.RESUMED), m(30, yt, MarkKind.PAUSED), m(30, yt, MarkKind.STOPPED),
            m(30, settings, MarkKind.RESUMED), m(40, settings, MarkKind.PAUSED), m(40, settings, MarkKind.STOPPED),
            m(40, home, MarkKind.RESUMED))
        val usage = VisibleUsage.of(marks, 0, 50 * 60_000L + 59_000L)
        val t = screenTimeOf(usage, LocalDate.of(2026, 10, 22), setOf(home), emptyList())
        // Settings is never blocked but counts, as in Digital Wellbeing; the home screen does not.
        assertEquals(40 * 60_000L, t.totalMs)
        assertEquals(listOf(AppTime(yt, 30 * 60_000L), AppTime(settings, 10 * 60_000L)), t.apps)
        assertEquals(10 * 60_000L, screenTimeOf(usage, LocalDate.of(2026, 10, 22), setOf(home), listOf(yt)).totalMs) // Unlimited YouTube
        validateScreenTime(t)
        assertThrows(IllegalArgumentException::class.java) { validateScreenTime(t.copy(date = "2026-02-30")) }
        assertThrows(IllegalArgumentException::class.java) { validateScreenTime(t.copy(apps = t.apps + t.apps)) }
    }

    @Test fun theParentReadsThePhonesOwnCount() {
        val raw = """{"state":"available","controlsOn":true,"policyVersion":3,"screenTime":{"date":"2026-10-22","totalMs":7920000,""" +
            """"apps":[{"packageName":"com.google.android.youtube","ms":1560000}]},"observedAt":"2026-10-22T17:00:00Z","receivedAt":"2026-10-22T17:00:01Z","events":[],"truncated":false}"""
        val reply = decodeActivityRead(raw)
        assertEquals(ActivityReadState.AVAILABLE, reply.state)
        assertEquals(ScreenTime("2026-10-22", 7_920_000L, listOf(AppTime(yt, 1_560_000L))), reply.screenTime)
        assertNull(decodeActivityRead(raw.replace(Regex(""""screenTime":\{.*?\]\},"""), "")).screenTime)
    }
}
