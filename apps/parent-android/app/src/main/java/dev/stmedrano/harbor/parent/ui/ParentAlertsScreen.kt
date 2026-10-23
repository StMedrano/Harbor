package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Message alerts", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        when (view.status) {
            AlertsViewStatus.NONE -> HarborCard { HarborEmptyState("No alerts yet.", art = HarborArt.LIGHTHOUSE) }
            AlertsViewStatus.EXPIRED -> HarborCard { HarborEmptyState("No alerts in the last 30 days.", art = HarborArt.CALM) }
            AlertsViewStatus.ACCESS_LOST -> HarborNote("You no longer have access to this device's alerts.", HarborTone.DANGER, emphasis = true)
            AlertsViewStatus.LOAD_FAILED -> HarborNote("Alerts could not be loaded. Check your connection and try again.", HarborTone.DANGER, emphasis = true)
            AlertsViewStatus.AVAILABLE -> AvailableAlerts(view)
        }
        if (view.offline) Text("Showing the alerts that last loaded. They could not be refreshed just now.", style = MaterialTheme.typography.bodySmall,
            color = harbor.ink2)
        HarborNote(ALERT_LIMITS_NOTICE, HarborTone.INFO)
        if (onRefresh != null && view.status != AlertsViewStatus.ACCESS_LOST) HarborOutlinedButton(onRefresh, Modifier.fillMaxWidth()) { Text("Refresh alerts") }
    }
}

@Composable
private fun AvailableAlerts(view: AlertsViewState) {
    if (view.notificationAccess == false) HarborNote("Notification access is off on this phone, so chat-app alerts are not arriving.", HarborTone.WARN)
    if (view.smsPermission == false) HarborNote("SMS permission is off on this phone, so text-message alerts are not arriving.", HarborTone.WARN)
    if (view.alerts.isEmpty()) {
        HarborCard { HarborEmptyState("No alerts yet.", art = HarborArt.LIGHTHOUSE) }
        return
    }
    view.alerts.forEach { alert -> AlertCard(alert) }
}

/** Urgent (coral) for a possible risk to someone's safety; every other flag is for review (amber). Always with the word. */
private fun alertTone(category: AlertCategory) = when (category) {
    AlertCategory.SELF_HARM, AlertCategory.VIOLENCE_THREAT -> HarborTagTone.HIGH
    else -> HarborTagTone.MEDIUM
}

/**
 * Design system `AlertCard`: the tag word first, then what was flagged (a possibility, never a verdict), then the app and
 * when. No message text, ever. An urgent card carries a coral border; the event owns the colour, never the child.
 */
@Composable
private fun AlertCard(alert: MessageAlert) {
    val contact = if (alert.savedContact) "a saved contact" else "someone not in contacts"
    val tone = alertTone(alert.category)
    HarborCard(Modifier.semantics(mergeDescendants = false) { contentDescription = "Alert: ${alertCategoryLabel(alert.category)}" },
        tag = if (tone == HarborTagTone.HIGH) "Urgent" else "Review", tagTone = tone, border = if (tone == HarborTagTone.HIGH) harbor.dan else harbor.line) {
        Text(alertCategoryLabel(alert.category), style = MaterialTheme.typography.titleMedium)
        Text("${alert.appLabel} · ${formatTimestamp(alert.at)} · from $contact", style = MaterialTheme.typography.bodySmall,
            color = harbor.ink3)
        alertGuidance(alert.category)?.let { guidance -> HarborNote(guidance, HarborTone.DANGER, emphasis = true) }
    }
}
