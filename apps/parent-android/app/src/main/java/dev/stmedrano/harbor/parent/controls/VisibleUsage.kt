package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.activity.AppTransition

/** The phone's own usage events that matter for time on screen. Names and times only. */
enum class MarkKind { RESUMED, PAUSED, STOPPED, SCREEN_OFF }
data class UsageMark(val atMs: Long, val packageName: String?, val kind: MarkKind)

/**
 * Time each app was on screen, and what is on screen now.
 *
 * An app counts from when it is resumed until it is stopped, not merely paused. That matches Android's own screen-time
 * figures and counts picture-in-picture, where a video keeps playing in a small window while the app is paused. An app
 * that is paused but not stopped and not in front is therefore [pictureInPicture] (or in split screen).
 * Moving between screens of one app (pause screen A, resume screen B, stop screen A) does not end its time.
 */
data class VisibleUsage(
    val intervals: Map<String, List<LongRange>>,
    /** The app in front now, or null (home screen, screen off, or unknown). */
    val resumed: String?,
    /** Apps showing without being in front, with when they were paused: picture-in-picture or split screen. */
    val pictureInPicture: Map<String, Long>,
    /** Apps still on screen at the end of the window, with when their time began. */
    val stillOpen: Map<String, Long> = emptyMap(),
) {
    fun appMs(packageName: String): Long = intervals[packageName].orEmpty().sumOf { it.last - it.first }

    /** Wall-clock time any counted app was on screen; two apps on screen at once (a video in picture-in-picture over a game) count once. */
    fun totalMs(excluded: Set<String>): Long {
        var total = 0L
        var reach = Long.MIN_VALUE
        for (range in intervals.filterKeys { it !in excluded }.values.flatten().sortedBy { it.first }) {
            val start = maxOf(range.first, reach)
            if (range.last > start) total += range.last - start
            reach = maxOf(reach, range.last)
        }
        return total
    }

    /**
     * The same time as resume/pause transitions, for the activity timeline's sessions. Build with `openUntilEnd = false`
     * so an app still on screen appears only as started, and its session is reported once it ends.
     */
    fun transitions(): List<AppTransition> = (intervals.flatMap { (pkg, ranges) ->
        ranges.flatMap { listOf(AppTransition(it.first, pkg, true), AppTransition(it.last, pkg, false)) }
    } + stillOpen.map { (pkg, since) -> AppTransition(since, pkg, true) }).sortedBy { it.atMs }

    companion object {
        /** Reads [marks] in time order and keeps the parts of each app's time on screen that fall between [fromMs] and [toMs]. */
        fun of(marks: List<UsageMark>, fromMs: Long, toMs: Long, openUntilEnd: Boolean = true): VisibleUsage {
            class Open(var since: Long, var paused: Boolean, var pausedAt: Long, var resumedAt: Long)
            val open = HashMap<String, Open>()
            val out = HashMap<String, MutableList<LongRange>>()
            fun close(pkg: String, at: Long) {
                val o = open.remove(pkg) ?: return
                val start = maxOf(o.since, fromMs)
                val end = minOf(at, toMs)
                if (end > start) out.getOrPut(pkg) { ArrayList() } += start..end
            }
            for (m in marks.withIndex().sortedWith(compareBy({ it.value.atMs }, { it.index })).map { it.value }) {
                if (m.atMs > toMs) break
                when (m.kind) {
                    MarkKind.SCREEN_OFF -> open.keys.toList().forEach { close(it, m.atMs) }
                    MarkKind.RESUMED -> {
                        val pkg = m.packageName ?: continue
                        val o = open.getOrPut(pkg) { Open(m.atMs, false, m.atMs, m.atMs) }
                        o.paused = false; o.resumedAt = m.atMs
                    }
                    MarkKind.PAUSED -> open[m.packageName ?: continue]?.let { it.paused = true; it.pausedAt = m.atMs }
                    // Stopped after a pause with no resume in between: the app has left the screen. A stop that follows a
                    // resume is an earlier screen of the same app finishing behind the new one.
                    MarkKind.STOPPED -> { val pkg = m.packageName ?: continue; if (open[pkg]?.paused == true) close(pkg, m.atMs) }
                }
            }
            val resumed = open.filterValues { !it.paused }.maxByOrNull { it.value.resumedAt }?.key
            val pip = open.filterValues { it.paused }.mapValues { it.value.pausedAt }
            val stillOpen = open.mapValues { it.value.since }
            if (openUntilEnd) open.keys.toList().forEach { close(it, toMs) }
            return VisibleUsage(out, resumed, pip, stillOpen)
        }
    }
}
