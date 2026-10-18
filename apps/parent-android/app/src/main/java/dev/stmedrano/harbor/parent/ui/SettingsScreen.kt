package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentRuntimeState
import dev.stmedrano.harbor.parent.notifications.ParentNotificationState
import dev.stmedrano.harbor.parent.notifications.ParentReceipt

@Composable
fun SettingsScreen(notifications: ParentNotificationState, runtime: ParentRuntimeState, available: Boolean,
    onEnable: () -> Unit, onRemove: () -> Unit, onSignOut: () -> Unit,
    developmentReceipt: ParentReceipt? = null, onExport: (() -> Unit)? = null, onChangeRole: (() -> Unit)? = null) {
    val enabled = available && !runtime.signingOut && !notifications.busy
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall)
        HarborPanel("Notifications") {
            Text(if (notifications.confirmed) "Registration confirmed" else "Registration unconfirmed", style = MaterialTheme.typography.titleMedium)
            Text("Notifications contain a generic hint. Harbor checks current access before showing family details.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            notifications.message?.let { Text(it) }
            HarborButton(onEnable, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Enable notifications") }
            HarborOutlinedButton(onRemove, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Remove notification registration") }
        }
        HarborPanel("Account") {
            HarborOutlinedButton(onSignOut, enabled = available && !runtime.signingOut,
                modifier = Modifier.fillMaxWidth()) { Text("Sign out this device") }
            if (runtime.signingOut) Text("Signing out…")
            if (runtime.cleanupConfirmed) Text("Current-device cleanup confirmed.")
            if (!available) Text("Sign in to manage this device. Live actions are disabled in the offline preview.")
            onChangeRole?.let { change ->
                HarborOutlinedButton(change, enabled = available && !runtime.signingOut,
                    modifier = Modifier.fillMaxWidth()) { Text("Change this phone's role") }
            }
        }
        developmentReceipt?.let { receipt ->
            HarborPanel("Development receipt evidence") {
                Text("Received: ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(receipt.receivedAt))}")
                Text("Event reference: ${receipt.route.resourceId}")
                if (onExport != null) HarborOutlinedButton(onExport, enabled = enabled) { Text("Export latest receipt") }
            }
        }
    }
}
