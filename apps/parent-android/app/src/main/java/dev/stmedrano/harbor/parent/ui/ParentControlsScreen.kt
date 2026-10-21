package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.access.AccessRequestItem
import dev.stmedrano.harbor.parent.access.AccessViewState
import dev.stmedrano.harbor.parent.access.AccessViewStatus
import dev.stmedrano.harbor.parent.access.ParentAccessModel
import dev.stmedrano.harbor.parent.access.accessRequestTitle
import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import dev.stmedrano.harbor.parent.alerts.alertInstantString
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.controls.*
import dev.stmedrano.harbor.parent.usage.UsageViewState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun rememberParentControlsView(model: ParentControlsModel?, identity: ParentIdentity, deviceId: String, resumeTick: Int = 0): ControlsViewState? {
    if (model == null) return null
    LaunchedEffect(model, identity, deviceId, resumeTick) {
        while (true) { model.load(identity, deviceId); delay(30_000) }
    }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentControlsModel.Scope(identity, deviceId) }?.view
}

@Composable
fun rememberParentAccessView(model: ParentAccessModel?, identity: ParentIdentity, deviceId: String, resumeTick: Int = 0): AccessViewState? {
    if (model == null) return null
    val pokes = model.refreshes.collectAsState().value
    LaunchedEffect(model, identity, deviceId, resumeTick, pokes) {
        while (true) { model.load(identity, deviceId); delay(30_000) }
    }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentAccessModel.Scope(identity, deviceId) }?.view
}

/**
 * A parent's yes: [pkg] may be used until [untilMs]. Ended grants and any earlier grant for the same app are dropped, and
 * the newest [CONTROLS_MAX_GRANTS] are kept, so the list never grows without bound.
 */
fun withGrant(controls: ControlsV1, pkg: String, untilMs: Long, nowMs: Long): ControlsV1 {
    val live = controls.grants.filter { it.packageName != pkg && runCatching { alertInstantMs(it.until) > nowMs }.getOrDefault(false) }
    return controls.copy(grants = (live + Grant(pkg, alertInstantString(untilMs))).takeLast(CONTROLS_MAX_GRANTS))
}

/** "just now", "3 min ago", "2 h ago" for a request time. */
fun askedAgo(requestedAt: String, nowMs: Long): String {
    val minutes = ((nowMs - runCatching { alertInstantMs(requestedAt) }.getOrDefault(nowMs)) / 60_000L).coerceAtLeast(0L)
    return when { minutes < 1 -> "just now"; minutes < 60 -> "$minutes min ago"; else -> "${minutes / 60} h ago" }
}

/** The rule an app has, as the parent picks it: no rule of its own, Unlimited (always allowed), a daily limit, or Blocked. */
enum class AppRule { ALLOWED, BLOCKED, LIMITED, ALWAYS }

fun ruleOf(controls: ControlsV1, pkg: String): AppRule = when {
    pkg in controls.blockedApps -> AppRule.BLOCKED
    controls.appLimits.any { it.packageName == pkg } -> AppRule.LIMITED
    pkg in controls.alwaysAllowed -> AppRule.ALWAYS
    else -> AppRule.ALLOWED
}

/** Sets one app's rule, removing any other rule for it, so the result always satisfies "one rule per app". */
fun withAppRule(controls: ControlsV1, pkg: String, rule: AppRule, minutes: Int = 60): ControlsV1 {
    val cleared = controls.copy(blockedApps = controls.blockedApps - pkg, appLimits = controls.appLimits.filterNot { it.packageName == pkg },
        alwaysAllowed = controls.alwaysAllowed - pkg)
    return when (rule) {
        AppRule.ALLOWED -> cleared
        AppRule.BLOCKED -> cleared.copy(blockedApps = cleared.blockedApps + pkg)
        AppRule.LIMITED -> cleared.copy(appLimits = cleared.appLimits + AppLimit(pkg, minutes.coerceIn(1, 1440)))
        AppRule.ALWAYS -> cleared.copy(alwaysAllowed = cleared.alwaysAllowed + pkg)
    }
}

const val DAILY_STEP_MINUTES = 15
const val DAILY_DEFAULT_MINUTES = 120
const val DAILY_MAX_MINUTES = 720

/** Moves the daily limit by [deltaMinutes], kept between 15 minutes and 12 hours. */
fun withDailyMinutes(controls: ControlsV1, deltaMinutes: Int): ControlsV1 {
    val current = controls.dailyLimit ?: return controls.copy(dailyLimit = DailyLimit(DAILY_DEFAULT_MINUTES))
    return controls.copy(dailyLimit = current.copy(minutes = (current.minutes + deltaMinutes).coerceIn(DAILY_STEP_MINUTES, DAILY_MAX_MINUTES)))
}

/** "Give 15 more min": extra time for [today] only, added to any extra already given today. */
fun withExtraTime(controls: ControlsV1, today: LocalDate, minutes: Int = 15): ControlsV1 {
    val limit = controls.dailyLimit ?: return controls
    val already = if (limit.extraDate == today.toString()) limit.extraMinutes else 0
    return controls.copy(dailyLimit = limit.copy(extraDate = today.toString(), extraMinutes = (already + minutes).coerceAtMost(720)))
}

/** Today's screen time that counts toward the daily limit: every reported app except the Unlimited ones. */
fun countedTodayMinutes(usage: UsageViewState?, controls: ControlsV1): Int? {
    val apps = usage?.apps?.takeIf { it.isNotEmpty() } ?: return null
    return (apps.filter { it.packageName !in controls.alwaysAllowed }.sumOf { it.foregroundMs ?: 0L } / 60_000L).toInt()
}

fun formatHm(minutes: Int): String = "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m"

/** What the app row says under the app's name, like the web app: its time today and what its rule does. */
fun appStatusLine(controls: ControlsV1, pkg: String, usedMinutes: Int, timeUp: Boolean): Pair<String, Boolean> {
    val today = if (usedMinutes > 0) "${usedMinutes}m today · " else ""
    return when (ruleOf(controls, pkg)) {
        AppRule.BLOCKED -> "Blocked" to true
        AppRule.ALWAYS -> "${today}Unlimited, works after time's up" to false
        AppRule.LIMITED -> {
            val limit = controls.appLimits.first { it.packageName == pkg }.minutesPerDay
            when {
                timeUp -> "${today}locked, time's up" to true
                usedMinutes >= limit -> "${usedMinutes}m today · limit reached" to true
                else -> "${usedMinutes}m of ${limit}m today" to false
            }
        }
        AppRule.ALLOWED -> if (timeUp) "${today}locked, time's up" to true
            else (if (usedMinutes > 0) "${usedMinutes}m today" else "No limit of its own") to false
    }
}

fun ruleLabel(controls: ControlsV1, pkg: String): String = when (ruleOf(controls, pkg)) {
    AppRule.BLOCKED -> "Blocked"
    AppRule.ALWAYS -> "Unlimited"
    AppRule.LIMITED -> "${controls.appLimits.first { it.packageName == pkg }.minutesPerDay}m/day"
    AppRule.ALLOWED -> "No limit"
}

private val clockFormat = DateTimeFormatter.ofPattern("h:mm a")
private fun clock(value: String) = runCatching { LocalTime.parse(value).format(clockFormat) }.getOrDefault(value)
private val clockChoices = (0 until 48).map { "%02d:%02d".format(it / 2, (it % 2) * 30) }
private val limitChoices = listOf(15, 30, 45, 60, 90, 120)

private data class AppEntry(val pkg: String, val label: String, val usedMinutes: Int)

/**
 * Controls for one child's phone, laid out like the web app's Controls tab: today's screen time against the daily
 * limit, pause, extra time, bedtime, and a rule per app. Every change is written straight away; the line at the top
 * says whether the phone has applied it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParentControlsScreen(view: ControlsViewState, childName: String, usage: UsageViewState?, controlsOn: Boolean?,
    onChange: (((ControlsV1) -> ControlsV1) -> Unit)?, onRefresh: (() -> Unit)?,
    requests: AccessViewState? = null, onAnswer: ((AccessRequestItem, Boolean) -> Unit)? = null) {
    when (view.status) {
        ControlsViewStatus.ACCESS_LOST -> { HarborNote("You no longer have access to this phone's controls.", HarborTone.DANGER, emphasis = true); return }
        ControlsViewStatus.LOAD_FAILED -> {
            HarborNote("Harbor couldn't load this phone's controls. Check your connection.", HarborTone.WARN)
            if (onRefresh != null) HarborSecondaryButton(onRefresh, Modifier.fillMaxWidth()) { Text("Try again") }
            return
        }
        ControlsViewStatus.AVAILABLE -> Unit
    }
    val c = view.controls
    val change = onChange ?: {}
    val enabled = onChange != null && !view.saving
    val today = LocalDate.now()
    val used = countedTodayMinutes(usage, c)
    val allowance = c.dailyLimit?.allowanceMinutes(today)
    val timeUp = allowance != null && used != null && used >= allowance
    val paused = PolicyEngine.isPaused(java.time.ZonedDateTime.now(), c)
    var sheetFor by remember { mutableStateOf<AppEntry?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (controlsOn == false) HarborNote("App controls are off on $childName's phone, so these rules aren't working right now. " +
            "Ask $childName to turn Harbor back on in Accessibility settings.", HarborTone.DANGER, emphasis = true)
        when {
            view.saveFailed -> HarborNote("That change wasn't saved. Check your connection and try again.", HarborTone.WARN)
            view.saving -> Text("Saving…", style = MaterialTheme.typography.bodySmall)
            view.applied -> Text("In force on $childName's phone.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Text("Sending to $childName's phone…", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onChange == null) HarborNote("You're offline. Changes can be made once Harbor reconnects.", HarborTone.WARN)
        requests?.takeIf { it.status == AccessViewStatus.AVAILABLE }?.let { RequestsPanel(it, childName, onAnswer) }

        if (paused) HarborNote(HarborTone.DANGER, emphasis = true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.pause.until?.let { "$childName's phone is paused until ${formatTimestamp(it)}" } ?: "$childName's phone is paused",
                    modifier = Modifier.weight(1f))
                HarborDestructiveButton({ change { it.copy(pause = ControlsPause(false, null)) } }, enabled = enabled) { Text("Resume") }
            }
        }

        HarborCard {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("Used today", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(used?.let(::formatHm) ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Daily limit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.dailyLimit == null) TextButton({ change { withDailyMinutes(it, 0) } }, enabled = enabled) { Text("Set a limit") }
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton({ change { withDailyMinutes(it, -DAILY_STEP_MINUTES) } }, Modifier.size(40.dp), enabled = enabled,
                            contentPadding = PaddingValues(0.dp)) { Text("−") }
                        Text(formatHm(c.dailyLimit.minutes), fontWeight = FontWeight.SemiBold)
                        OutlinedButton({ change { withDailyMinutes(it, DAILY_STEP_MINUTES) } }, Modifier.size(40.dp), enabled = enabled,
                            contentPadding = PaddingValues(0.dp)) { Text("+") }
                    }
                }
            }
            if (allowance != null) {
                LinearProgressIndicator({ if (used == null) 0f else (used.toFloat() / allowance).coerceIn(0f, 1f) },
                    Modifier.fillMaxWidth().height(10.dp),
                    color = if (timeUp) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outline)
                val extra = c.dailyLimit?.takeIf { it.extraDate == today.toString() && it.extraMinutes > 0 }?.extraMinutes
                Text(when {
                    used == null -> "Today's time shows once the phone reports its usage."
                    timeUp -> "Time's up"
                    else -> formatHm(allowance - used) + " left today"
                } + (extra?.let { " · includes $it extra min" } ?: ""), style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (timeUp) FontWeight.SemiBold else FontWeight.Normal)
                TextButton({ change { it.copy(dailyLimit = null) } }, enabled = enabled, contentPadding = PaddingValues(0.dp)) { Text("Turn off daily limit") }
            } else Text("No daily limit. Apps can be used as long as their own rules allow.", style = MaterialTheme.typography.bodySmall)
        }
        if (timeUp) HarborNote(HarborTone.DANGER) {
            Text("Time's up. $childName's apps are locked.", fontWeight = FontWeight.SemiBold)
            Text("Only Unlimited apps work until midnight. Calls and texts always work.")
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (paused) HarborSecondaryButton({ change { it.copy(pause = ControlsPause(false, null)) } }, Modifier.weight(1f), enabled = enabled) { Text("Resume phone") }
            else HarborDestructiveButton({ change { it.copy(pause = ControlsPause(true, null)) } }, Modifier.weight(1f), enabled = enabled) { Text("Pause phone now") }
            HarborSecondaryButton({ change { withExtraTime(it, today) } }, Modifier.weight(1f), enabled = enabled && c.dailyLimit != null) { Text("Give 15 more min") }
        }

        HarborPanel("Schedules") {
            val bed = c.bedtime ?: ControlsBedtime(false, "21:00", "07:00", Weekday.entries)
            HarborRow("Bedtime", subtitle = "${clock(bed.start)} – ${clock(bed.end)} · ${daysText(bed.days)} · phone locks", trailing = {
                Switch(bed.enabled, { on -> change { it.copy(bedtime = bed.copy(enabled = on, days = bed.days.ifEmpty { Weekday.entries })) } },
                    enabled = enabled)
            })
            if (bed.enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    ChoiceMenu("From ${clock(bed.start)}", clockChoices.filter { it != bed.end }, ::clock, enabled) { v -> change { it.copy(bedtime = bed.copy(start = v)) } }
                    ChoiceMenu("To ${clock(bed.end)}", clockChoices.filter { it != bed.start }, ::clock, enabled) { v -> change { it.copy(bedtime = bed.copy(end = v)) } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Weekday.entries.forEach { day ->
                        FilterChip(day in bed.days, {
                            val days = if (day in bed.days) bed.days - day else Weekday.entries.filter { it in bed.days || it == day }
                            change { it.copy(bedtime = bed.copy(days = days, enabled = days.isNotEmpty())) }
                        }, label = { Text(day.name.take(1) + day.name.drop(1).take(2).lowercase()) }, enabled = enabled)
                    }
                }
            }
        }

        HarborPanel("App limits") {
            Text("Hard stops. When an app's time is used up it locks until tomorrow.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val reported = usage?.apps.orEmpty().map { AppEntry(it.packageName, it.label ?: it.packageName, ((it.foregroundMs ?: 0L) / 60_000L).toInt()) }
            val ruled = (c.blockedApps + c.appLimits.map { it.packageName } + c.alwaysAllowed).filter { pkg -> reported.none { it.pkg == pkg } }
            val apps = reported + ruled.map { AppEntry(it, it, 0) }
            if (apps.isEmpty()) Text("$childName's phone hasn't reported its apps yet.", style = MaterialTheme.typography.bodySmall)
            apps.forEachIndexed { index, app ->
                val (line, alert) = appStatusLine(c, app.pkg, app.usedMinutes, timeUp && ruleOf(c, app.pkg) != AppRule.ALWAYS)
                HarborRow(app.label, divider = index > 0, onClick = if (enabled) ({ sheetFor = app }) else null,
                    leading = { HarborAvatar(app.label, 34.dp) },
                    trailing = { OutlinedButton({ sheetFor = app }, enabled = enabled) { Text(ruleLabel(c, app.pkg)) } })
                Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 46.dp),
                    color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (alert) FontWeight.SemiBold else FontWeight.Normal)
            }
            Text("Phone calls, texts, Settings and Harbor always work.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    sheetFor?.let { app ->
        ModalBottomSheet({ sheetFor = null }) {
            Column(Modifier.padding(horizontal = 18.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${app.label} for $childName", style = MaterialTheme.typography.titleLarge)
                Text("${app.usedMinutes}m used today. Limits are hard stops.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                val current = ruleOf(c, app.pkg)
                val minutes = c.appLimits.firstOrNull { it.packageName == app.pkg }?.minutesPerDay
                val options = listOf(Triple("No limit", "Uses the daily time like other apps", (AppRule.ALLOWED to 0)),
                    Triple("Unlimited", "Keeps working after daily time is up", (AppRule.ALWAYS to 0))) +
                    limitChoices.map { Triple("$it min a day", "Locks when used up", AppRule.LIMITED to it) } +
                    Triple("Blocked", "Can't be opened", AppRule.BLOCKED to 0)
                options.forEach { (title, detail, pick) ->
                    val selected = current == pick.first && (pick.first != AppRule.LIMITED || minutes == pick.second)
                    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton) {
                        sheetFor = null
                        change { withAppRule(it, app.pkg, pick.first, pick.second) }
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(title, fontWeight = FontWeight.Medium)
                            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        RadioButton(selected, null)
                    }
                }
            }
        }
    }
}

/** A child's requests from the block screen, like the web app: what they asked for, when, and Allow / Deny. */
@Composable
private fun RequestsPanel(r: AccessViewState, childName: String, onAnswer: ((AccessRequestItem, Boolean) -> Unit)?) {
    val now = System.currentTimeMillis()
    val answered = r.requests.filter { it.status != "pending" }.take(3)
    if (r.pending.isEmpty() && answered.isEmpty() && r.problem == null) return
    HarborPanel("Requests") {
        r.problem?.let { HarborNote(it, HarborTone.WARN) }
        if (r.pending.isEmpty()) Text("No requests waiting.", style = MaterialTheme.typography.bodySmall)
        r.pending.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text(accessRequestTitle(item), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text("$childName asked ${askedAgo(item.requestedAt, now)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val busy = r.answering != null || onAnswer == null
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HarborButton({ onAnswer?.invoke(item, true) }, Modifier.weight(1f), enabled = !busy) {
                    Text(if (r.answering == item.requestId) "Sending…" else "Allow 15 min")
                }
                HarborSecondaryButton({ onAnswer?.invoke(item, false) }, Modifier.weight(1f), enabled = !busy) { Text("Deny") }
            }
        }
        if (answered.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            answered.forEach { item ->
                val outcome = when (item.status) { "approved" -> "Allowed 15 min"; "denied" -> "Said no"; else -> "Not answered in time" }
                Text("$outcome · ${accessRequestTitle(item)} · ${askedAgo(item.requestedAt, now)}", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun daysText(days: List<Weekday>): String = when (days.toSet()) {
    Weekday.entries.toSet() -> "every night"
    setOf(Weekday.SUN, Weekday.MON, Weekday.TUE, Weekday.WED, Weekday.THU) -> "school nights"
    else -> days.joinToString(", ") { it.name.take(1) + it.name.drop(1).take(2).lowercase() }
}

@Composable
private fun ChoiceMenu(label: String, choices: List<String>, show: (String) -> String, enabled: Boolean, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }, enabled = enabled) { Text(label) }
        DropdownMenu(open, { open = false }) {
            choices.forEach { choice -> DropdownMenuItem({ Text(show(choice)) }, { open = false; onChoose(choice) }) }
        }
    }
}
