package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.controls.ControlsAccess
import dev.stmedrano.harbor.parent.controls.ControlsV1
import dev.stmedrano.harbor.parent.controls.ExemptApps
import dev.stmedrano.harbor.parent.controls.totalSinceMidnight
import dev.stmedrano.harbor.parent.controls.PolicyEngine
import dev.stmedrano.harbor.parent.controls.UsageToday
import dev.stmedrano.harbor.parent.controls.Weekday
import dev.stmedrano.harbor.parent.controls.appLabel
import dev.stmedrano.harbor.parent.profile.ProfileLease
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.ZonedDateTime

/** What the child dashboard needs to show app controls truthfully. */
data class ChildControlsUi(
    val controls: ControlsV1?,
    val enabled: Boolean,
    val labels: Map<String, String>,
    val remainingMs: Map<String, Long>,
    val now: ZonedDateTime,
    /** Screen time left today under the daily limit, or null when there is no daily limit. */
    val totalRemainingMs: Long? = null,
    val onTurnOn: () -> Unit,
)

/** Re-reads whether controls are switched on and the time left for limited apps, on resume and every few seconds. */
@Composable
fun rememberChildControlsUi(graph: ParentApplication, lease: ProfileLease, resumeTick: Int, onTurnOn: () -> Unit): ChildControlsUi {
    val hostState = graph.childControlsHost?.state?.collectAsState()?.value
    val controls = hostState?.controls
    var enabled by remember(lease) { mutableStateOf(ControlsAccess.isEnabled(graph)) }
    var remaining by remember(lease) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var labels by remember(lease) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var now by remember(lease) { mutableStateOf(ZonedDateTime.now()) }
    var totalLeft by remember(lease) { mutableStateOf<Long?>(null) }
    LaunchedEffect(lease, resumeTick, controls) {
        while (true) {
            enabled = ControlsAccess.isEnabled(graph)
            now = ZonedDateTime.now()
            val policy = controls
            if (policy != null) withContext(Dispatchers.IO) {
                val nowMs = now.toInstant().toEpochMilli()
                val midnight = now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli()
                remaining = policy.appLimits.associate { limit ->
                    limit.packageName to (PolicyEngine.remainingMs(limit.packageName, policy,
                        UsageToday.foregroundSinceMidnight(graph, limit.packageName, nowMs, midnight)) ?: 0L)
                }
                totalLeft = policy.dailyLimit?.let {
                    val excluded = ExemptApps.resolve(graph) + policy.alwaysAllowed
                    PolicyEngine.totalRemainingMs(now, policy, UsageToday.totalSinceMidnight(graph, nowMs, midnight, excluded))
                }
                labels = (policy.blockedApps + policy.appLimits.map { it.packageName } + policy.alwaysAllowed).associateWith { appLabel(graph, it) }
            }
            delay(5000)
        }
    }
    return ChildControlsUi(controls, enabled, labels, remaining, now, totalLeft, onTurnOn)
}

/** The rules in plain words, one line each. Empty when a parent has not set any. */
fun controlsSummary(ui: ChildControlsUi): List<String> {
    val c = ui.controls ?: return emptyList()
    val label = { pkg: String -> ui.labels[pkg] ?: pkg }
    return buildList {
        if (PolicyEngine.isPaused(ui.now, c)) add("Your phone is paused by a parent. Calls and texts still work.")
        c.dailyLimit?.let { d ->
            val left = ui.totalRemainingMs
            val leftText = when {
                left == null -> ""
                left <= 0L -> " — used up for today. Only Unlimited apps work until tomorrow."
                else -> " — ${formatHm(maxOf(1L, left / 60_000L).toInt())} left today"
            }
            add("Screen time: ${formatHm(d.allowanceMinutes(ui.now.toLocalDate()))} a day$leftText")
        }
        c.bedtime?.takeIf { it.enabled }?.let { b ->
            val days = if (b.days.toSet() == Weekday.entries.toSet()) "every night" else "on " + b.days.joinToString(", ") { it.name.lowercase().replaceFirstChar(Char::uppercase) }
            add("Bedtime is ${b.start} to ${b.end} $days." + if (PolicyEngine.isBedtime(ui.now, c)) " It's bedtime now." else "")
        }
        if (c.blockedApps.isNotEmpty()) add("Blocked: " + c.blockedApps.joinToString(", ") { label(it) } + ".")
        c.appLimits.forEach { limit ->
            val left = ui.remainingMs[limit.packageName]
            val leftText = when {
                left == null -> ""
                left <= 0L -> " — used up for today"
                else -> " — ${maxOf(1L, left / 60_000L)} min left today"
            }
            add("${label(limit.packageName)}: ${limit.minutesPerDay} min a day$leftText")
        }
        if (c.alwaysAllowed.isNotEmpty()) add("Unlimited: " + c.alwaysAllowed.joinToString(", ") { label(it) } + ".")
    }
}

/**
 * "What Harbor controls": the current rules, whether they are being applied, and that only a parent can change them.
 * There is deliberately no control here to turn rules off.
 */
@Composable
fun ControlsPanel(ui: ChildControlsUi) {
    val lines = controlsSummary(ui)
    HarborPanel("What Harbor controls") {
        if (lines.isEmpty()) Text("Your parents haven't set any app rules.")
        else lines.forEach { Text(it) }
        if (ui.enabled) {
            Text("App controls are on.", style = MaterialTheme.typography.titleSmall)
        } else {
            HarborNote("App controls are off on this phone, so these rules aren't being applied. Your parents are told when this happens.",
                HarborTone.WARN, emphasis = true)
            HarborButton(ui.onTurnOn, Modifier.fillMaxWidth()) { Text("Turn on app controls") }
        }
        Text("Harbor only sees which app is open, never what's on the screen. Only your parents can change these rules.",
            style = MaterialTheme.typography.bodySmall)
    }
}
