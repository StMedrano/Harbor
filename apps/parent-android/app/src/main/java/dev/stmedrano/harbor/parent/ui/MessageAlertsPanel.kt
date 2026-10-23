package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.alerts.AlertAccess
import dev.stmedrano.harbor.parent.alerts.AlertHostState
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
 * Message access in one line: "Message access enabled" when both permissions are on, otherwise only what is missing and
 * its action. There is deliberately no disable control; only a parent can turn watching off. What is watched, what is and
 * is not sent, and the limits are written out in About.
 */
@Composable
fun MessageAlertsSection(ui: ChildAlertsUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (ui.access.notificationAccess && ui.access.smsPermission) {
            HarborStatus("Message access enabled", ok = true, glyph = HarborGlyph.ALERTS, detail = if (ui.host.uploadFailing) "Can't reach Harbor. Trying again." else null)
        } else {
            if (!ui.access.notificationAccess) {
                HarborStatus("Notification access off", ok = false, glyph = HarborGlyph.ALERTS)
                HarborButton(ui.onOpenNotificationAccess, Modifier.fillMaxWidth()) { Text("Turn on notification access") }
            }
            if (!ui.access.smsPermission) {
                HarborStatus("Text message access off", ok = false, glyph = HarborGlyph.ALERTS)
                HarborButton(ui.onAllowSms, Modifier.fillMaxWidth()) { Text("Allow text messages") }
                HarborOutlinedButton(ui.onOpenAppSettings, Modifier.fillMaxWidth()) { Text("Open app settings") }
            }
        }
    }
}
