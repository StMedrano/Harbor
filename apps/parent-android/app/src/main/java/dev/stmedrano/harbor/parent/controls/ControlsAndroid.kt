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
        // The installer is never blocked as an app; removing an app is guarded on its own (UninstallGuard).
        out += removalScreens(app).installers
        return out
    }

    /**
     * Apps whose time never counts toward the daily limit: the home screen, keyboards, System UI and Harbor itself (its
     * block screen is on screen while an app is blocked). Phone, Messages and Settings are never blocked but do count,
     * as they do in Digital Wellbeing.
     */
    fun uncounted(context: Context): Set<String> {
        val app = context.applicationContext
        val out = hashSetOf("android", "com.android.systemui", app.packageName)
        out += try { resolvedHomePackages(app) } catch (_: Exception) { emptySet() }
        try {
            app.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.forEach { out += it.packageName }
        } catch (_: Exception) {}
        return out
    }
}

/** The apps and screens that handle "remove this package" on this phone (declared in the manifest's queries). */
fun removalScreens(context: Context): RemovalScreens {
    val pm = context.packageManager
    val found = listOf(Intent.ACTION_DELETE, Intent.ACTION_UNINSTALL_PACKAGE).flatMap { action ->
        try { pm.queryIntentActivities(Intent(action, android.net.Uri.parse("package:${context.packageName}")), 0) }
        catch (_: Exception) { emptyList() }
    }.map { it.activityInfo }
    return RemovalScreens(UninstallGuard.INSTALLERS + found.map { it.packageName }, found.map { it.name }.toSet())
}

/** The phone's usage events between [fromMs] and [toMs], reduced to what time on screen needs: names, kinds and times. */
fun usageMarks(context: Context, fromMs: Long, toMs: Long): List<UsageMark> = try {
    val events = context.getSystemService(UsageStatsManager::class.java)?.queryEvents(fromMs, toMs)
    val out = ArrayList<UsageMark>()
    if (events != null) {
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> MarkKind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> MarkKind.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> MarkKind.STOPPED
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> MarkKind.SCREEN_OFF
                else -> null
            } ?: continue
            out += UsageMark(event.timeStamp, event.packageName, kind)
        }
    }
    out
} catch (_: Exception) { emptyList() }

/** Today's time on screen since local midnight, from the phone's own usage events (see [VisibleUsage]). */
object UsageToday {
    // Start well before midnight so an app already on screen at midnight is seen and clipped to midnight.
    fun read(context: Context, nowMs: Long, midnightMs: Long): VisibleUsage =
        VisibleUsage.of(usageMarks(context, midnightMs - 6 * 3_600_000L, nowMs), midnightMs, nowMs)
    fun foregroundSinceMidnight(context: Context, packageName: String, nowMs: Long, midnightMs: Long): Long =
        read(context, nowMs, midnightMs).appMs(packageName)
}

/** Today's screen time that counts toward the daily limit: every app except [excluded]. */
fun UsageToday.totalSinceMidnight(context: Context, nowMs: Long, midnightMs: Long, excluded: Set<String>): Long =
    read(context, nowMs, midnightMs).totalMs(excluded)

/** Every app's time on screen in a window as start/end transitions, for the activity timeline. Names and times only. */
fun usageTransitions(context: Context, fromMs: Long, toMs: Long): List<dev.stmedrano.harbor.parent.activity.AppTransition> =
    VisibleUsage.of(usageMarks(context, fromMs, toMs), fromMs, toMs, openUntilEnd = false).transitions()

fun appLabel(context: Context, packageName: String): String = try {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString().trim().take(64).ifEmpty { "This app" }
} catch (_: Exception) { "This app" }
