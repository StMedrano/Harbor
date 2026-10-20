package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.alerts.alertInstantString
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.controls.*
import dev.stmedrano.harbor.parent.usage.UsageAppRow
import kotlinx.coroutines.delay

@Composable
fun rememberParentControlsView(model: ParentControlsModel?, identity: ParentIdentity, deviceId: String, resumeTick: Int = 0): ControlsViewState? {
    if (model == null) return null
    LaunchedEffect(model, identity, deviceId, resumeTick) {
        while (true) { model.load(identity, deviceId); delay(30_000) }
    }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentControlsModel.Scope(identity, deviceId) }?.view
}

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

private val limitChoices = listOf(15, 30, 45, 60, 90, 120, 180, 240)
private val clockChoices = (0 until 48).map { "%02d:%02d".format(it / 2, (it % 2) * 30) }

/**
 * App controls for one child's phone: pause, bedtime, and a rule per app chosen from the apps the phone reported.
 * Changes are saved together; the status line says whether the phone has applied the latest version.
 */
@Composable
fun ParentControlsScreen(view: ControlsViewState, apps: List<UsageAppRow>, controlsOn: Boolean?, onSave: (ControlsV1) -> Unit, onRefresh: (() -> Unit)?) {
    HarborPanel("App controls") {
        when (view.status) {
            ControlsViewStatus.ACCESS_LOST -> { HarborNote("You no longer have access to this phone's controls.", HarborTone.DANGER, emphasis = true); return@HarborPanel }
            ControlsViewStatus.LOAD_FAILED -> {
                HarborNote("Harbor couldn't load this phone's controls. Check your connection.", HarborTone.WARN)
                if (onRefresh != null) HarborSecondaryButton(onRefresh, Modifier.fillMaxWidth()) { Text("Try again") }
                return@HarborPanel
            }
            ControlsViewStatus.AVAILABLE -> Unit
        }
        var draft by remember(view.version) { mutableStateOf(view.controls) }
        if (controlsOn == false) HarborNote("App controls are off on the phone, so these rules aren't being applied right now.", HarborTone.DANGER, emphasis = true)
        Text(if (view.applied) "In force on the phone." else "Waiting for the phone to apply the latest change.",
            style = MaterialTheme.typography.bodySmall)

        Text("Pause", style = MaterialTheme.typography.titleSmall)
        if (draft.pause.active) {
            Text(draft.pause.until?.let { "Paused until ${formatTimestamp(it)}." } ?: "Paused until you resume it.")
            HarborOutlinedButton({ draft = draft.copy(pause = ControlsPause(false, null)) }, Modifier.fillMaxWidth()) { Text("Resume") }
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HarborOutlinedButton({ draft = draft.copy(pause = ControlsPause(true, null)) }, Modifier.weight(1f)) { Text("Pause now") }
            HarborOutlinedButton({ draft = draft.copy(pause = ControlsPause(true, alertInstantString(System.currentTimeMillis() + 3_600_000L))) },
                Modifier.weight(1f)) { Text("Pause 1 hour") }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        val bed = draft.bedtime ?: ControlsBedtime(false, "21:00", "07:00", Weekday.entries)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bedtime", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Switch(bed.enabled, { on -> draft = draft.copy(bedtime = bed.copy(enabled = on, days = if (on && bed.days.isEmpty()) Weekday.entries else bed.days)) })
        }
        if (bed.enabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                ChoiceMenu("From ${bed.start}", clockChoices.filter { it != bed.end }) { draft = draft.copy(bedtime = bed.copy(start = it)) }
                ChoiceMenu("To ${bed.end}", clockChoices.filter { it != bed.start }) { draft = draft.copy(bedtime = bed.copy(end = it)) }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Weekday.entries.forEach { day ->
                    FilterChip(day in bed.days, {
                        val days = if (day in bed.days) bed.days - day else Weekday.entries.filter { it in bed.days || it == day }
                        draft = draft.copy(bedtime = bed.copy(days = days, enabled = days.isNotEmpty()))
                    }, label = { Text(day.name.take(1) + day.name.drop(1).take(2).lowercase()) })
                }
            }
            Text("Bedtime starting on a chosen evening runs into the next morning.", style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Text("Apps", style = MaterialTheme.typography.titleSmall)
        if (apps.isEmpty()) Text("The phone hasn't reported its apps yet.", style = MaterialTheme.typography.bodySmall)
        apps.forEach { app ->
            val rule = ruleOf(draft, app.packageName)
            val minutes = draft.appLimits.firstOrNull { it.packageName == app.packageName }?.minutesPerDay ?: 60
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(app.label ?: app.packageName, modifier = Modifier.weight(1f))
                ChoiceMenu(when (rule) {
                    AppRule.ALLOWED -> "Allowed"; AppRule.BLOCKED -> "Blocked"
                    AppRule.LIMITED -> "$minutes min/day"; AppRule.ALWAYS -> "Always allowed"
                }, listOf("Allowed", "Blocked") + limitChoices.map { "$it min/day" } + "Always allowed") { choice ->
                    draft = when {
                        choice == "Allowed" -> withAppRule(draft, app.packageName, AppRule.ALLOWED)
                        choice == "Blocked" -> withAppRule(draft, app.packageName, AppRule.BLOCKED)
                        choice == "Always allowed" -> withAppRule(draft, app.packageName, AppRule.ALWAYS)
                        else -> withAppRule(draft, app.packageName, AppRule.LIMITED, choice.substringBefore(" ").toInt())
                    }
                }
            }
        }
        Text("Phone calls, texts, Settings and Harbor always stay available.", style = MaterialTheme.typography.bodySmall)

        if (view.saveFailed) HarborNote("Your changes weren't saved. Check your connection and try again.", HarborTone.WARN)
        val valid = runCatching { validateControls(draft) }.isSuccess
        HarborButton({ onSave(draft) }, Modifier.fillMaxWidth(), enabled = !view.saving && valid && draft != view.controls) {
            Text(if (view.saving) "Saving…" else "Save changes")
        }
    }
}

@Composable
private fun ChoiceMenu(label: String, choices: List<String>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text(label) }
        DropdownMenu(open, { open = false }) {
            choices.forEach { choice -> DropdownMenuItem({ Text(choice) }, { open = false; onChoose(choice) }) }
        }
    }
}
