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
enum class SetupStepId { LOCATION, LOCATION_ALWAYS, NOTIFICATIONS, USAGE_ACCESS, NOTIFICATION_ACCESS }

data class SetupStep(val id: SetupStepId, val title: String, val why: String, val on: Boolean)

/** What the phone currently allows, read fresh from Android. The gate never trusts a remembered answer. */
/** [alerts] is null in builds without message alerts; the message step is then not part of setup. */
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

/** What a person is agreeing to, shown before Android's own screen and separately for each permission. */
data class HarborDisclosure(val title: String, val body: String)

/**
 * The words Google Play requires before a sensitive permission: what is collected, why, and who sees it, in the app itself,
 * ending in a deliberate tap. Android's own dialog never appears until that tap. Steps with no sensitive data have none.
 */
fun setupDisclosure(id: SetupStepId): HarborDisclosure? = when (id) {
    SetupStepId.LOCATION -> HarborDisclosure("Share this phone's location",
        "Harbor Family collects location data to show where this phone is to your parents, and on your family's map. Places are kept for up to 30 days. Your parent can stop this by removing the phone from the family.")
    SetupStepId.LOCATION_ALWAYS -> HarborDisclosure("Share location all the time",
        "Harbor Family collects location data to show where this phone is to your parents and on your family's map, even when the app is closed or not in use. A notification stays on this phone the whole time so you can always see it is happening. Places are kept for up to 30 days.")
    SetupStepId.USAGE_ACCESS -> HarborDisclosure("Share screen time",
        "Harbor Family reads which apps are used on this phone and for how long, and sends that to your parents so they can see screen time. It is kept for up to 30 days. It never reads messages, web pages, photos or contacts.")
    SetupStepId.NOTIFICATION_ACCESS -> HarborDisclosure("Check chat notifications",
        "Harbor Family reads new notifications from chat and messaging apps on this phone, including their text, to look for possible concerns such as bullying or threats. That check happens on this phone. If something is flagged, your parents are sent only the kind of concern, the app and the time, never the message or who sent it.")
    SetupStepId.NOTIFICATIONS -> null
}

/** Separate from every other disclosure, as Google Play asks of an app using the Accessibility service. */
val ACCESSIBILITY_DISCLOSURE = HarborDisclosure("Use the Accessibility service for app controls",
    "Harbor Family uses Android's Accessibility service to see which app is on screen, so it can apply the rules your parent set: blocking apps, time limits, bedtime and pause. Your parents are sent which apps were opened or blocked, and when. It cannot read what is on the screen, and it does not type or tap for you.")

/** The disclosure and its deliberate choice. Declining changes nothing, and the step simply stays off. */
@Composable
fun HarborConsentDialog(disclosure: HarborDisclosure, onAgree: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(onDismissRequest = onDecline, containerColor = harbor.s2,
        title = { Text(disclosure.title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(disclosure.body, color = harbor.ink2) },
        confirmButton = { TextButton(onAgree) { Text("Agree and continue") } },
        dismissButton = { TextButton(onDecline) { Text("Not now") } })
}

/**
 * Child setup: every permission Harbor needs, each with the reason, and no way past until all are on. Android shows its own
 * screen for each one, so Harbor can only ask; the buttons say so.
 */
@Composable
fun ChildSetupGate(steps: List<SetupStep>, onTurnOn: (SetupStepId) -> Unit, onOpenAppSettings: () -> Unit, onFinish: () -> Unit) {
    var doc by remember { mutableStateOf<LegalDoc?>(null) }
    val openDoc = doc
    if (openDoc != null) { LegalScreen(openDoc) { doc = null }; return }
    var asking by remember { mutableStateOf<SetupStepId?>(null) }
    asking?.let { id ->
        setupDisclosure(id)?.let { disclosure ->
            HarborConsentDialog(disclosure, onAgree = { asking = null; onTurnOn(id) }, onDecline = { asking = null })
        }
    }
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
                        else HarborButton(onClick = { if (setupDisclosure(step.id) != null) asking = step.id else onTurnOn(step.id) }, modifier = Modifier.semantics { contentDescription = "Turn on ${step.title}" }) { Text("Turn on") }
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
