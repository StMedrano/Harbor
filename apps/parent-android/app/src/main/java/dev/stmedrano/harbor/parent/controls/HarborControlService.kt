package dev.stmedrano.harbor.parent.controls

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
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
 * policy says an app may not be used now, it covers the app with Harbor's block screen explaining why.
 *
 * Every few seconds it also checks the phone's own usage events: an app brought back from picture-in-picture does not
 * always announce a new window, and a video in picture-in-picture keeps playing over other apps. Time limits count the
 * time an app is on screen, picture-in-picture included (see [VisibleUsage]).
 */
class HarborControlService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var exempt: Set<String> = emptySet()
    private var foreground: String? = null
    private val warned = HashMap<String, Int>()
    private var warnedDay: LocalDate? = null
    private val limitRecorded = HashSet<String>()
    private val lastBlockRecorded = HashMap<String, Long>()
    /** Picture-in-picture windows already brought back to be covered, by app and the time they were paused. */
    private val reclaimed = HashMap<String, Long>()
    /** When the block screen was last opened over each app, to notice if Android did not show it. */
    private val coveredAt = HashMap<String, Long>()
    private val tick = Runnable { evaluate() }
    private val scan = Runnable { scanScreen() }

    override fun onServiceConnected() {
        exempt = ExemptApps.resolve(this)
        // A pause or a new block arriving while an app is open applies to that app now, not at the next window change.
        ControlsBridge.onPolicyChanged = { handler.post { evaluate(); scanScreen() } }
        ControlsBridge.setConnected(true)
        handler.post(scan)
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

    private fun hasRules(p: ControlsV1) = p.pause.active || p.bedtime?.enabled == true || p.blockedApps.isNotEmpty() ||
        p.appLimits.isNotEmpty() || p.dailyLimit != null

    /** Today's time on screen, read only when a limit needs it. */
    private fun usageFor(policy: ControlsV1, now: ZonedDateTime): VisibleUsage? =
        if (policy.appLimits.isEmpty() && policy.dailyLimit == null) null
        else UsageToday.read(this, now.toInstant().toEpochMilli(), now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli())

    private fun decide(pkg: String, now: ZonedDateTime, policy: ControlsV1, usage: VisibleUsage?): Decision {
        // Always-allowed ("Unlimited") apps neither count toward nor stop at the day's total screen time.
        val total = if (policy.dailyLimit != null && pkg !in policy.alwaysAllowed) usage?.totalMs(exempt + policy.alwaysAllowed) ?: 0L else 0L
        return PolicyEngine.decide(pkg, now, policy, usage?.appMs(pkg) ?: 0L, exempt, total)
    }

    private fun evaluate(known: VisibleUsage? = null) {
        handler.removeCallbacks(tick)
        val pkg = foreground ?: return
        val policy = ControlsBridge.policy ?: return
        if (pkg in exempt) return
        val now = ZonedDateTime.now()
        val nowMs = now.toInstant().toEpochMilli()
        val usage = known ?: usageFor(policy, now)
        when (val decision = decide(pkg, now, policy, usage)) {
            is Decision.Block -> block(pkg, decision, nowMs)
            Decision.Allow -> {
                // Under a parent's yes the app's limits do not apply; re-check when the yes ends so the app closes then.
                val grantLeft = PolicyEngine.grantRemainingMs(pkg, now, policy)
                val appLeft = PolicyEngine.remainingMs(pkg, policy, usage?.appMs(pkg) ?: 0L).takeIf { grantLeft == null }
                val totalLeft = (if (policy.dailyLimit != null && pkg !in policy.alwaysAllowed)
                    PolicyEngine.totalRemainingMs(now, policy, usage?.totalMs(exempt + policy.alwaysAllowed) ?: 0L) else null)
                    .takeIf { grantLeft == null }
                appLeft?.let { warnIfLow(pkg, it, now.toLocalDate()) }
                totalLeft?.let { warnIfLow(TOTAL_KEY, it, now.toLocalDate()) }
                // Re-check while the app stays open: a limit can run out and a pause or bedtime can begin.
                val soonest = listOfNotNull(appLeft, totalLeft, grantLeft).minOrNull()
                handler.postDelayed(tick, soonest?.coerceIn(1_000L, CHECK_EVERY_MS) ?: CHECK_EVERY_MS)
            }
        }
    }

    /** The app in front and any app in picture-in-picture, from usage events, checked every few seconds. */
    private fun scanScreen() {
        handler.removeCallbacks(scan)
        try {
            val policy = ControlsBridge.policy
            if (policy != null && hasRules(policy)) {
                val now = ZonedDateTime.now()
                val nowMs = now.toInstant().toEpochMilli()
                val usage = UsageToday.read(this, nowMs, now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli())
                // Still in front well after its block screen was opened: Android did not show it, so leave the app instead.
                usage.resumed?.let { pkg -> coveredAt[pkg]?.let { at -> if (nowMs - at in COVER_GRACE_MS..COVER_GIVE_UP_MS &&
                    decide(pkg, now, policy, usage) is Decision.Block) { coveredAt.remove(pkg); performGlobalAction(GLOBAL_ACTION_HOME) } } }
                usage.resumed?.takeIf { it != foreground && it !in exempt }?.let { foreground = it; evaluate(usage) }
                for ((pkg, pausedAt) in usage.pictureInPicture) {
                    if (pkg in exempt || pkg == usage.resumed || reclaimed[pkg] == pausedAt) continue
                    val decision = decide(pkg, now, policy, usage) as? Decision.Block ?: continue
                    reclaimed[pkg] = pausedAt
                    reclaim(pkg, decision, nowMs)
                }
            }
        } catch (_: Exception) { /* The next scan tries again. */ }
        finally { handler.postDelayed(scan, SCAN_EVERY_MS) }
    }

    /**
     * A video in picture-in-picture floats above every app, so a block screen cannot cover it. Opening the app again
     * brings it back to full screen, and the block screen then covers it, which stops it.
     */
    private fun reclaim(pkg: String, decision: Decision.Block, nowMs: Long) {
        try {
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } catch (_: Exception) { /* Covered below regardless. */ }
        handler.postDelayed({ block(pkg, decision, nowMs) }, RECLAIM_DELAY_MS)
    }

    private fun block(pkg: String, decision: Decision.Block, nowMs: Long) {
        val reason = decision.reason
        val label = appLabel(this, pkg)
        // Cover the app straight away. Pressing Home first raced the block screen and hid it, and made video apps drop
        // into picture-in-picture and keep playing; the block screen's own "Go to home screen" leaves from there.
        try { startActivity(BlockActivity.intent(this, pkg, label, reason, decision.daily)); coveredAt[pkg] = nowMs }
        catch (_: Exception) { performGlobalAction(GLOBAL_ACTION_HOME) }
        foreground = null
        // "Daily time limit reached" on the timeline names one app's own limit; the day's total shows as blocked opens.
        if (reason == BlockReason.LIMIT_REACHED && !decision.daily && limitRecorded.add("$pkg@${LocalDate.now()}")) {
            ControlsBridge.record(ControlEvent(ActivityKind.LIMIT_REACHED, nowMs, pkg, label))
        }
        // One "tried to open" event per app per minute is enough for the parent's timeline.
        if ((lastBlockRecorded[pkg] ?: 0L) + 60_000L <= nowMs) {
            lastBlockRecorded[pkg] = nowMs
            ControlsBridge.record(ControlEvent(ActivityKind.BLOCKED_OPEN, nowMs, pkg, label, reason))
        }
    }

    /** [pkg] is an app with its own limit, or [TOTAL_KEY] for the day's total screen time. */
    private fun warnIfLow(pkg: String, remainingMs: Long, day: LocalDate) {
        if (warnedDay != day) { warned.clear(); limitRecorded.clear(); warnedDay = day }
        val step = when { remainingMs <= 60_000L -> 1; remainingMs <= 5 * 60_000L -> 5; else -> return }
        if ((warned[pkg] ?: Int.MAX_VALUE) <= step) return
        warned[pkg] = step
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App time limits", NotificationManager.IMPORTANCE_DEFAULT))
        val label = if (pkg == TOTAL_KEY) "screen time" else appLabel(this, pkg)
        val text = if (step == 1) "About 1 minute of $label left today." else "About 5 minutes of $label left today."
        try {
            manager.notify(pkg.hashCode(), NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Harbor Family").setContentText(text)
                .setAutoCancel(true).build())
        } catch (_: SecurityException) { /* Notifications off: the block screen still explains when time runs out. */ }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(scan)
        ControlsBridge.onPolicyChanged = null
        ControlsBridge.setConnected(false)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "harbor-app-limits"
        private const val CHECK_EVERY_MS = 30_000L
        private const val SCAN_EVERY_MS = 5_000L
        private const val RECLAIM_DELAY_MS = 700L
        private const val COVER_GRACE_MS = 2_500L
        private const val COVER_GIVE_UP_MS = 15_000L
        private const val TOTAL_KEY = "harbor.total"
    }
}
