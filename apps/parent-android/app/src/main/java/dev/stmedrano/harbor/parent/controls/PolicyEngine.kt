package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import java.time.LocalDate
import java.time.ZonedDateTime

sealed interface Decision {
    data object Allow : Decision
    data class Block(val reason: BlockReason) : Decision
}

/**
 * Decides whether the app now on screen may stay there. Pure: the caller supplies the time (in the phone's own zone),
 * the policy, how long the app has been used today, and the apps that are always exempt on this phone (Harbor, the
 * launcher, the dialer, Settings, System UI and the default SMS app). Order matters: an exempt or always-allowed app is
 * never blocked; then pause, bedtime, an outright block, and finally the daily limit.
 */
object PolicyEngine {
    fun decide(packageName: String, now: ZonedDateTime, policy: ControlsV1, usedTodayMs: Long, exempt: Set<String>): Decision {
        if (packageName in exempt || packageName in policy.alwaysAllowed) return Decision.Allow
        if (isPaused(now, policy)) return Decision.Block(BlockReason.PAUSED)
        if (isBedtime(now, policy)) return Decision.Block(BlockReason.BEDTIME)
        if (packageName in policy.blockedApps) return Decision.Block(BlockReason.BLOCKED_APP)
        val remaining = remainingMs(packageName, policy, usedTodayMs)
        if (remaining != null && remaining <= 0L) return Decision.Block(BlockReason.LIMIT_REACHED)
        return Decision.Allow
    }

    /** A pause without an end lasts until a parent resumes; one with an end stops on its own at that instant. */
    fun isPaused(now: ZonedDateTime, policy: ControlsV1): Boolean {
        if (!policy.pause.active) return false
        val until = policy.pause.until ?: return true
        return now.toInstant().toEpochMilli() < alertInstantMs(until)
    }

    /**
     * The bedtime window belongs to the day it starts on. A window that crosses midnight (21:00–07:00) that started on
     * a chosen day is still in force the next morning, even if the next day is not chosen.
     */
    fun isBedtime(now: ZonedDateTime, policy: ControlsV1): Boolean {
        val b = policy.bedtime ?: return false
        if (!b.enabled) return false
        val start = clockMinutes(b.start)
        val end = clockMinutes(b.end)
        val minute = now.hour * 60 + now.minute
        val today = Weekday.of(now.dayOfWeek)
        val yesterday = Weekday.of(now.dayOfWeek.minus(1))
        return if (start < end) {
            today in b.days && minute in start until end
        } else {
            (today in b.days && minute >= start) || (yesterday in b.days && minute < end)
        }
    }

    /** Time left today for a limited app, or null when the app has no limit. Never negative. */
    fun remainingMs(packageName: String, policy: ControlsV1, usedTodayMs: Long): Long? {
        val limit = policy.appLimits.firstOrNull { it.packageName == packageName } ?: return null
        return maxOf(0L, limit.minutesPerDay * 60_000L - maxOf(0L, usedTodayMs))
    }

    /** Daily limits reset at local midnight on the phone. */
    fun limitDay(now: ZonedDateTime): LocalDate = now.toLocalDate()
}
