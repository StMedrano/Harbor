package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.activity.AppTransition
import dev.stmedrano.harbor.parent.activity.sessionsFrom
import org.junit.Assert.*
import org.junit.Test

class VisibleUsageTest {
    private val yt = "com.google.android.youtube"
    private val home = "com.google.android.apps.nexuslauncher"
    private val game = "com.example.game"
    private fun m(at: Long, pkg: String?, kind: MarkKind) = UsageMark(at * 60_000L, pkg, kind)
    private fun min(ms: Long) = ms / 60_000L

    @Test fun pictureInPictureCountsUntilTheAppStopsAndIsReported() {
        // Watch 10 min, go home (YouTube drops to picture-in-picture and keeps playing) for 16 min, then close it.
        val marks = listOf(m(0, yt, MarkKind.RESUMED), m(10, yt, MarkKind.PAUSED), m(10, home, MarkKind.RESUMED), m(26, yt, MarkKind.STOPPED))
        val u = VisibleUsage.of(marks, 0, 30 * 60_000L)
        assertEquals(26, min(u.appMs(yt)))
        val during = VisibleUsage.of(marks.take(3), 0, 15 * 60_000L)
        assertEquals(home, during.resumed)
        assertEquals(mapOf(yt to 10 * 60_000L), during.pictureInPicture)
        assertEquals(15, min(during.appMs(yt)))
    }

    @Test fun leavingNormallyEndsAtTheStopAndMovingBetweenScreensDoesNotEndTime() {
        val leave = listOf(m(0, yt, MarkKind.RESUMED), m(5, yt, MarkKind.PAUSED), m(5, yt, MarkKind.STOPPED), m(5, home, MarkKind.RESUMED))
        assertEquals(5, min(VisibleUsage.of(leave, 0, 60 * 60_000L).appMs(yt)))
        assertTrue(VisibleUsage.of(leave, 0, 60 * 60_000L).pictureInPicture.isEmpty())
        // Screen A paused, screen B resumed, screen A stopped: still in YouTube.
        val inside = listOf(m(0, yt, MarkKind.RESUMED), m(4, yt, MarkKind.PAUSED), m(4, yt, MarkKind.RESUMED), m(4, yt, MarkKind.STOPPED))
        val u = VisibleUsage.of(inside, 0, 9 * 60_000L)
        assertEquals(yt, u.resumed)
        assertEquals(9, min(u.appMs(yt)))
    }

    @Test fun screenOffEndsEverythingAndTheWindowClipsTime() {
        val marks = listOf(m(-30, yt, MarkKind.RESUMED), m(10, yt, MarkKind.PAUSED), m(20, null, MarkKind.SCREEN_OFF))
        val u = VisibleUsage.of(marks, 0, 60 * 60_000L)
        assertEquals(20, min(u.appMs(yt)))
        assertNull(u.resumed)
        assertTrue(u.pictureInPicture.isEmpty())
    }

    @Test fun totalCountsOverlapOnceAndLeavesOutExcludedApps() {
        // A video in picture-in-picture over a game: 10 minutes of screen time, not 20.
        val marks = listOf(m(0, yt, MarkKind.RESUMED), m(5, yt, MarkKind.PAUSED), m(5, game, MarkKind.RESUMED),
            m(15, game, MarkKind.PAUSED), m(15, game, MarkKind.STOPPED), m(15, yt, MarkKind.STOPPED), m(15, home, MarkKind.RESUMED))
        val u = VisibleUsage.of(marks, 0, 20 * 60_000L)
        assertEquals(15, min(u.appMs(yt)))
        assertEquals(10, min(u.appMs(game)))
        assertEquals(15, min(u.totalMs(setOf(home))))
        assertEquals(10, min(u.totalMs(setOf(home, yt))))
    }

    @Test fun timelineSessionsIncludePictureInPictureAndWaitForAnOpenOneToEnd() {
        val marks = listOf(m(0, yt, MarkKind.RESUMED), m(3, yt, MarkKind.PAUSED), m(8, yt, MarkKind.STOPPED), m(9, game, MarkKind.RESUMED))
        val t = VisibleUsage.of(marks, 0, 12 * 60_000L, openUntilEnd = false).transitions()
        assertEquals(listOf(AppTransition(0, yt, true), AppTransition(8 * 60_000L, yt, false), AppTransition(9 * 60_000L, game, true)), t)
        val (sessions, cursor) = sessionsFrom(t, 0, 12 * 60_000L, emptySet())
        assertEquals(listOf(8L), sessions.map { min(it.endMs - it.startMs) })
        assertEquals(9 * 60_000L, cursor)
    }
}
