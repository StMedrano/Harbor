package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.alerts.*
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.delay

/** Loads the parent's read-only alerts for one device and returns the view valid for exactly this parent and device. */
@Composable
fun rememberParentAlertsView(model: ParentAlertsModel?, identity: ParentIdentity, deviceId: String, resumeTick: Int = 0): AlertsViewState? {
    if (model == null) return null
    LaunchedEffect(model, identity, deviceId, resumeTick) {
        while (true) { model.load(identity, deviceId); delay(60_000) }
    }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentAlertsModel.Scope(identity, deviceId) }?.view
}

/**
 * The parent alerts list and detail. Every item is a possibility, never a verdict, and carries no message text —
 * only the app, the category, the time and whether the sender was a saved contact. Self-harm concerns show
 * supportive guidance and crisis resources. The standing limits notice is always visible.
 */
@Composable
fun ParentAlertsScreen(view: AlertsViewState, onRefresh: (() -> Unit)? = null) {
    HarborPanel("Message alerts") {
        when (view.status) {
            AlertsViewStatus.NONE -> Text("No concerns have been flagged on this phone. Harbor starts watching after the child allows notification access and SMS in Harbor on their phone.")
            AlertsViewStatus.EXPIRED -> Text("Any alerts this phone sent are older than 30 days and were removed.")
            AlertsViewStatus.ACCESS_LOST -> HarborNote("You no longer have access to this device's alerts.", HarborTone.DANGER, emphasis = true)
            AlertsViewStatus.LOAD_FAILED -> HarborNote("Alerts could not be loaded. Check your connection and try again.", HarborTone.DANGER, emphasis = true)
            AlertsViewStatus.AVAILABLE -> AvailableAlerts(view)
        }
        if (view.offline) Text("Showing the alerts that last loaded. They could not be refreshed just now.", style = MaterialTheme.typography.bodySmall)
        HarborNote(ALERT_LIMITS_NOTICE, HarborTone.INFO)
        if (onRefresh != null && view.status != AlertsViewStatus.ACCESS_LOST) HarborSecondaryButton(onRefresh, Modifier.fillMaxWidth()) { Text("Refresh alerts") }
    }
}

@Composable
private fun AvailableAlerts(view: AlertsViewState) {
    if (view.notificationAccess == false) HarborNote("Notification access is off on this phone, so chat-app alerts are not arriving.", HarborTone.WARN)
    if (view.smsPermission == false) HarborNote("SMS permission is off on this phone, so text-message alerts are not arriving.", HarborTone.WARN)
    if (view.alerts.isEmpty()) {
        Text("No concerns have been flagged yet.")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        view.alerts.forEach { alert -> AlertRow(alert) }
    }
}

@Composable
private fun AlertRow(alert: MessageAlert) {
    val contact = if (alert.savedContact) "a saved contact" else "someone not in contacts"
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Alert: ${alertCategoryLabel(alert.category)}" },
    ) {
        Text(alertCategoryLabel(alert.category), style = MaterialTheme.typography.titleSmall)
        Text("${alert.appLabel} · ${formatTimestamp(alert.at)} · from $contact", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        alertGuidance(alert.category)?.let { guidance -> HarborNote(guidance, HarborTone.DANGER, emphasis = true) }
    }
}
