package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.alerts.AlertAccess
import dev.stmedrano.harbor.parent.alerts.AlertHostState
import dev.stmedrano.harbor.parent.alerts.MonitoredApps
import dev.stmedrano.harbor.parent.profile.ProfileLease
import kotlinx.coroutines.delay

/** What the child dashboard needs to explain message-alert watching and fix its permissions. */
data class ChildAlertsUi(
    val access: AlertAccess,
    val host: AlertHostState,
    val onOpenNotificationAccess: () -> Unit,
    val onAllowSms: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/** Re-reads the phone's real notification-access and SMS state on resume and every few seconds while visible; it never trusts a cached answer. */
@Composable
fun rememberChildAlertsUi(graph: ParentApplication, lease: ProfileLease, resumeTick: Int, onOpenNotificationAccess: () -> Unit,
    onAllowSms: () -> Unit, onOpenAppSettings: () -> Unit): ChildAlertsUi {
    var access by remember(lease) { mutableStateOf(graph.alertAccess()) }
    val host = graph.childAlertHost?.state?.collectAsState()?.value ?: AlertHostState()
    LaunchedEffect(lease, resumeTick) {
        while (true) {
            access = graph.alertAccess()
            delay(3000)
        }
    }
    LaunchedEffect(lease, resumeTick) { graph.refreshChildAlerts(lease) }
    return ChildAlertsUi(access, host, onOpenNotificationAccess, onAllowSms, onOpenAppSettings)
}

/**
 * "What Harbor watches": a plain disclosure of which apps are watched, which categories can be flagged, exactly what
 * is and is not sent, and the honest limits. Watching starts by itself once Android's permissions are given, and only
 * a parent can turn it off (by removing this device in the parent app), so there is deliberately no disable control.
 */
@Composable
fun MessageAlertsPanel(ui: ChildAlertsUi) {
    HarborPanel("What Harbor watches") {
        Text("Harbor watches new notifications from these apps and your incoming text messages: ${MonitoredApps.labels().joinToString(", ")}.")
        Text("It checks them on this phone for possible concerns — bullying or harassment, self-harm or wellbeing, adult content, drugs or alcohol, violence or threats, and contact from an unknown adult.")
        Text("When something is flagged, your parents are sent only the category, which app it came from, the time, and whether the sender is saved in your contacts. Your parents are never sent the message text, who sent it, a phone number, or any part of the message.")
        Text("Harbor can miss things: muted or hidden message previews and end-to-end encrypted messages may not be seen, and it makes mistakes in both directions. It does not read your whole conversations.")
        Text("Only your parents can turn this off, by removing this device in the parent app.")

        if (!ui.access.notificationAccess) {
            HarborNote("Notification access is off, so chat-app messages are not being checked.", HarborTone.WARN, emphasis = true)
            HarborButton(ui.onOpenNotificationAccess, Modifier.fillMaxWidth()) { Text("Turn on notification access") }
        }
        if (!ui.access.smsPermission) {
            HarborNote("Text-message checking is off.", HarborTone.WARN, emphasis = true)
            HarborButton(ui.onAllowSms, Modifier.fillMaxWidth()) { Text("Allow text messages") }
            HarborOutlinedButton(ui.onOpenAppSettings, Modifier.fillMaxWidth()) { Text("Open app settings") }
        }
        if (ui.access.notificationAccess && ui.access.smsPermission) {
            Text("Harbor is watching.", style = MaterialTheme.typography.titleSmall)
            if (ui.host.uploadFailing) Text("Your phone could not reach Harbor just now. It will try again.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
