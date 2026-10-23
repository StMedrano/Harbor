package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.alerts.AlertAccess
import dev.stmedrano.harbor.parent.legal.LegalDoc
import dev.stmedrano.harbor.parent.location.LocationAccess

/** The permissions a child phone needs before Harbor works. New features add a step here; nothing else has to change. */
enum class SetupStepId { LOCATION, LOCATION_ALWAYS, NOTIFICATIONS, USAGE_ACCESS, NOTIFICATION_ACCESS, SMS }

data class SetupStep(val id: SetupStepId, val title: String, val why: String, val on: Boolean)

/** What the phone currently allows, read fresh from Android. The gate never trusts a remembered answer. */
/** [alerts] is null in builds without message alerts; the two message steps are then not part of setup. */
data class PermissionSnapshot(val location: LocationAccess, val notificationsOn: Boolean, val usageGranted: Boolean, val alerts: AlertAccess? = null)

fun childSetupSteps(snapshot: PermissionSnapshot): List<SetupStep> = listOf(
    SetupStep(SetupStepId.LOCATION, "Location", "Share where this phone is.",
        snapshot.location.fineGranted && snapshot.location.servicesOn),
    SetupStep(SetupStepId.LOCATION_ALWAYS, "Location all the time", "Keep sharing when Harbor is closed.",
        snapshot.location.fineGranted && snapshot.location.backgroundGranted),
    SetupStep(SetupStepId.NOTIFICATIONS, "Notifications", "Show the sharing notice and check-in requests.",
        snapshot.notificationsOn),
    SetupStep(SetupStepId.USAGE_ACCESS, "Usage access", "Measure screen time. Never blocks apps.",
        snapshot.usageGranted),
) + (snapshot.alerts?.let { alerts -> listOf(
    SetupStep(SetupStepId.NOTIFICATION_ACCESS, "Notification access",
        "Check chat notifications on this phone.",
        alerts.notificationAccess),
    SetupStep(SetupStepId.SMS, "Text messages", "Check texts on this phone.", alerts.smsPermission),
) } ?: emptyList())

fun setupComplete(steps: List<SetupStep>) = steps.isNotEmpty() && steps.all { it.on }

/**
 * Remembers whether the gate has been shown, so that finishing setup is a deliberate tap, and brings the gate back the
 * moment any permission is turned off later.
 */
data class SetupGateMemory(val sawGate: Boolean = false, val finished: Boolean = false)

fun SetupGateMemory.observe(complete: Boolean) = if (!complete) SetupGateMemory(sawGate = true, finished = false) else this
fun SetupGateMemory.finish(complete: Boolean) = if (complete) copy(finished = true) else this
fun SetupGateMemory.visible(complete: Boolean) = !complete || (sawGate && !finished)

/**
 * Child setup: every permission Harbor needs, each with the reason, and no way past until all are on. Android shows its own
 * screen for each one, so Harbor can only ask; the buttons say so.
 */
@Composable
fun ChildSetupGate(steps: List<SetupStep>, onTurnOn: (SetupStepId) -> Unit, onOpenAppSettings: () -> Unit, onFinish: () -> Unit) {
    var doc by remember { mutableStateOf<LegalDoc?>(null) }
    val openDoc = doc
    if (openDoc != null) { LegalScreen(openDoc) { doc = null }; return }
    val done = steps.count { it.on }
    val complete = setupComplete(steps)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HarborScreenHeader("Almost ready", "Set up this phone",
            subtitle = "Turn these on to finish. What Harbor shares, and what it never does, is explained below.")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HarborMeter(if (steps.isEmpty()) 0f else done.toFloat() / steps.size,
                Modifier.weight(1f).semantics { contentDescription = "Permissions turned on" })
            Text("$done of ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HarborCard {
            steps.forEachIndexed { index, step ->
                HarborRow(step.title, subtitle = step.why, divider = index > 0,
                    leading = {
                        // Design system check-in step dots: filled sea glass when done, a hairline ring while waiting.
                        Surface(shape = CircleShape, color = if (step.on) harbor.acc else harbor.s2,
                            border = if (step.on) null else androidx.compose.foundation.BorderStroke(2.dp, harbor.line),
                            modifier = Modifier.size(26.dp)) {
                            if (step.on) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                HarborIcon(HarborGlyph.TOAST_SUCCESS, harbor.s, Modifier.size(16.dp), strokeWidth = 2.6f)
                            }
                        }
                    },
                    trailing = {
                        if (step.on) Text("On", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        else HarborButton(onClick = { onTurnOn(step.id) }, modifier = Modifier.semantics { contentDescription = "Turn on ${step.title}" }) { Text("Turn on") }
                    })
            }
        }
        HarborNote("Each button opens Android's own screen. If one is turned off later, Harbor brings you back here.")
        HarborButton(onClick = onFinish, enabled = complete, modifier = Modifier.fillMaxWidth()) { Text("Finish setup") }
        if (!complete) Text("Turn on everything above to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HarborOutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
        HarborOutlinedButton(onClick = { doc = LegalDoc.CHILD_ABOUT }, modifier = Modifier.fillMaxWidth()) { Text("What Harbor shares and why") }
        PolicyLinks { doc = it }
    }
}
