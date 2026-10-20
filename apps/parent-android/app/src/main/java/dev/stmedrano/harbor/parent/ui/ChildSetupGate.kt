package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.alerts.AlertAccess
import dev.stmedrano.harbor.parent.location.LocationAccess

/** The permissions a child phone needs before Harbor works. New features add a step here; nothing else has to change. */
enum class SetupStepId { LOCATION, LOCATION_ALWAYS, NOTIFICATIONS, USAGE_ACCESS, NOTIFICATION_ACCESS, SMS }

data class SetupStep(val id: SetupStepId, val title: String, val why: String, val on: Boolean)

/** What the phone currently allows, read fresh from Android. The gate never trusts a remembered answer. */
/** [alerts] is null in builds without message alerts; the two message steps are then not part of setup. */
data class PermissionSnapshot(val location: LocationAccess, val notificationsOn: Boolean, val usageGranted: Boolean, val alerts: AlertAccess? = null)

fun childSetupSteps(snapshot: PermissionSnapshot): List<SetupStep> = listOf(
    SetupStep(SetupStepId.LOCATION, "Location", "Lets Harbor know where this phone is while the app is open.",
        snapshot.location.fineGranted && snapshot.location.servicesOn),
    SetupStep(SetupStepId.LOCATION_ALWAYS, "Location all the time", "Keeps sharing working when Harbor is closed or the screen is off.",
        snapshot.location.fineGranted && snapshot.location.backgroundGranted),
    SetupStep(SetupStepId.NOTIFICATIONS, "Notifications", "Shows the sharing notification and tells you when a parent asks you to check in.",
        snapshot.notificationsOn),
    SetupStep(SetupStepId.USAGE_ACCESS, "Usage access", "Lets Harbor measure screen time. It never limits or blocks apps.",
        snapshot.usageGranted),
) + (snapshot.alerts?.let { alerts -> listOf(
    SetupStep(SetupStepId.NOTIFICATION_ACCESS, "Notification access",
        "Lets Harbor check messages in chat apps on this phone. Your parents only get the app, the kind of concern and the time, never the words.",
        alerts.notificationAccess),
    SetupStep(SetupStepId.SMS, "Text messages", "Lets Harbor check texts the same way. Your parents never get the words.", alerts.smsPermission),
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
    val done = steps.count { it.on }
    val complete = setupComplete(steps)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Set up this phone", style = MaterialTheme.typography.headlineSmall)
        Text("Harbor needs everything below turned on, and each one says what it is for. Harbor never records your screen, your camera or your microphone.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinearProgressIndicator(progress = { if (steps.isEmpty()) 0f else done.toFloat() / steps.size },
                modifier = Modifier.weight(1f).semantics { contentDescription = "Permissions turned on" },
                color = MaterialTheme.colorScheme.secondary, trackColor = MaterialTheme.colorScheme.outline)
            Text("$done of ${steps.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HarborCard {
            steps.forEachIndexed { index, step ->
                HarborRow(step.title, subtitle = step.why, divider = index > 0,
                    leading = {
                        Surface(shape = CircleShape, color = if (step.on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceContainerHighest,
                            border = if (step.on) null else androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.onSurfaceVariant),
                            modifier = Modifier.size(26.dp)) {
                            if (step.on) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("✓", color = MaterialTheme.colorScheme.onSecondary, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    },
                    trailing = {
                        if (step.on) Text("On", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                        else HarborButton(onClick = { onTurnOn(step.id) }, modifier = Modifier.semantics { contentDescription = "Turn on ${step.title}" }) { Text("Turn on") }
                    })
            }
        }
        HarborNote("Each button opens Android's own screen. Harbor can't switch these on for you. If one is turned off later, Harbor brings you back here and your parents see that sharing has stopped.")
        HarborButton(onClick = onFinish, enabled = complete, modifier = Modifier.fillMaxWidth()) { Text("Finish setup") }
        if (!complete) Text("Turn on everything above to continue.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HarborOutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.fillMaxWidth()) { Text("Open app settings") }
    }
}
