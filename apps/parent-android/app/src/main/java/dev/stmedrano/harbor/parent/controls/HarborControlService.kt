package dev.stmedrano.harbor.parent.controls

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import dev.stmedrano.harbor.parent.activity.ActivityKind
import dev.stmedrano.harbor.parent.activity.BlockReason
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Enforces app controls. It listens only for "a different window came to the front" and reads only that window's
 * package name; it is configured so it cannot read window content (see res/xml/harbor_control_service.xml). When the
 * policy says the app may not be used now, it sends the phone home and shows Harbor's block screen explaining why.
 */
class HarborControlService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var exempt: Set<String> = emptySet()
    private var foreground: String? = null
    private val warned = HashMap<String, Int>()
    private var warnedDay: LocalDate? = null
    private val limitRecorded = HashSet<String>()
    private val lastBlockRecorded = HashMap<String, Long>()
    private val tick = Runnable { evaluate() }

    override fun onServiceConnected() {
        exempt = ExemptApps.resolve(this)
        ControlsBridge.setConnected(true)
        ControlsBridge.record(ControlEvent(ActivityKind.PROTECTION_ON, System.currentTimeMillis()))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // The keyboard and system overlays come and go above the app; they do not change which app is in use.
        if (pkg in exempt && pkg != packageName && pkg != "com.android.settings" && !isHome(pkg)) return
        foreground = pkg
        evaluate()
    }

    private fun isHome(pkg: String) = try { pkg in dev.stmedrano.harbor.parent.usage.resolvedHomePackages(this) } catch (_: Exception) { false }

    private fun evaluate() {
        handler.removeCallbacks(tick)
        val pkg = foreground ?: return
        val policy = ControlsBridge.policy ?: return
        if (pkg in exempt) return
        val now = ZonedDateTime.now()
        val midnight = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
        val nowMs = now.toInstant().toEpochMilli()
        val limited = policy.appLimits.any { it.packageName == pkg }
        val used = if (limited) UsageToday.foregroundSinceMidnight(this, pkg, nowMs, midnight) else 0L
        when (val decision = PolicyEngine.decide(pkg, now, policy, used, exempt)) {
            is Decision.Block -> block(pkg, decision.reason, policy, nowMs)
            Decision.Allow -> {
                PolicyEngine.remainingMs(pkg, policy, used)?.let { warnIfLow(pkg, it, now.toLocalDate()) }
                // Re-check while the app stays open: a limit can run out and a pause or bedtime can begin.
                val remaining = PolicyEngine.remainingMs(pkg, policy, used)
                val next = if (remaining != null) remaining.coerceIn(1_000L, CHECK_EVERY_MS) else CHECK_EVERY_MS
                handler.postDelayed(tick, next)
            }
        }
    }

    private fun block(pkg: String, reason: BlockReason, policy: ControlsV1, nowMs: Long) {
        val label = appLabel(this, pkg)
        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(BlockActivity.intent(this, label, reason))
        foreground = null
        if (reason == BlockReason.LIMIT_REACHED && limitRecorded.add("$pkg@${LocalDate.now()}")) {
            ControlsBridge.record(ControlEvent(ActivityKind.LIMIT_REACHED, nowMs, pkg, label))
        }
        // One "tried to open" event per app per minute is enough for the parent's timeline.
        if ((lastBlockRecorded[pkg] ?: 0L) + 60_000L <= nowMs) {
            lastBlockRecorded[pkg] = nowMs
            ControlsBridge.record(ControlEvent(ActivityKind.BLOCKED_OPEN, nowMs, pkg, label, reason))
        }
    }

    private fun warnIfLow(pkg: String, remainingMs: Long, day: LocalDate) {
        if (warnedDay != day) { warned.clear(); limitRecorded.clear(); warnedDay = day }
        val step = when { remainingMs <= 60_000L -> 1; remainingMs <= 5 * 60_000L -> 5; else -> return }
        if ((warned[pkg] ?: Int.MAX_VALUE) <= step) return
        warned[pkg] = step
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App time limits", NotificationManager.IMPORTANCE_DEFAULT))
        val label = appLabel(this, pkg)
        val text = if (step == 1) "About 1 minute of $label left today." else "About 5 minutes of $label left today."
        try {
            manager.notify(pkg.hashCode(), NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Harbor").setContentText(text)
                .setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Notifications off: the block screen still explains when time runs out. */ }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        ControlsBridge.setConnected(false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "harbor-app-limits"
        private const val CHECK_EVERY_MS = 30_000L
    }
}
