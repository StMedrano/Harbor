package dev.stmedrano.harbor.parent.controls

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import dev.stmedrano.harbor.parent.usage.resolvedHomePackages

/** Whether Harbor's accessibility service is switched on in Settings. Read fresh every time; never cached. */
object ControlsAccess {
    fun isEnabled(context: Context): Boolean = try {
        val mine = ComponentName(context, HarborControlService::class.java)
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').mapNotNull(ComponentName::unflattenFromString).any { it == mine }
    } catch (_: Exception) { false }

    /** Opens the system Accessibility list; the child turns Harbor on there. There is no automatic grant. */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Apps that are never blocked on this phone, whatever the policy says: Harbor itself, the home screen, System UI,
 * Settings (so the phone stays recoverable), the keyboard, the dialer and in-call screens (emergency calls), the default
 * SMS app (so a child can always reach a parent), and Android's own permission and chooser dialogs.
 */
object ExemptApps {
    private val fixed = setOf(
        "android", "com.android.systemui", "com.android.settings", "com.android.phone", "com.android.server.telecom",
        "com.android.emergency", "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller",
    )
    fun resolve(context: Context): Set<String> {
        val app = context.applicationContext
        val out = HashSet(fixed)
        out += app.packageName
        out += try { resolvedHomePackages(app) } catch (_: Exception) { emptySet() }
        try { app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage?.let(out::add) } catch (_: Exception) {}
        try { Telephony.Sms.getDefaultSmsPackage(app)?.let(out::add) } catch (_: Exception) {}
        try {
            app.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { out += it.packageName }
        } catch (_: Exception) {}
        return out
    }
}

/** Time [packageName] has been in the foreground since local midnight, from the phone's own usage events. */
object UsageToday {
    fun foregroundSinceMidnight(context: Context, packageName: String, nowMs: Long, midnightMs: Long): Long = try {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return 0L
        // Start a little before midnight so a session already running at midnight is seen and clipped to midnight.
        val events = manager.queryEvents(midnightMs - 6 * 3_600_000L, nowMs) ?: return 0L
        val transitions = ArrayList<Pair<Long, Boolean>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName != packageName) continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> transitions += event.timeStamp to true
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> transitions += event.timeStamp to false
            }
        }
        foregroundMs(transitions, midnightMs, nowMs)
    } catch (_: Exception) { 0L }
}

/** Every app's resume/pause transitions in a window, for the activity timeline. Names and times only. */
fun usageTransitions(context: Context, fromMs: Long, toMs: Long): List<dev.stmedrano.harbor.parent.activity.AppTransition> = try {
    val manager = context.getSystemService(UsageStatsManager::class.java)
    val events = manager?.queryEvents(fromMs, toMs)
    val out = ArrayList<dev.stmedrano.harbor.parent.activity.AppTransition>()
    if (events != null) {
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName ?: continue
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> out += dev.stmedrano.harbor.parent.activity.AppTransition(event.timeStamp, pkg, true)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> out += dev.stmedrano.harbor.parent.activity.AppTransition(event.timeStamp, pkg, false)
            }
        }
    }
    out
} catch (_: Exception) { emptyList() }

fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().trim().take(64).ifEmpty { "This app" }
} catch (_: Exception) { "This app" }
