package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentRuntimeState
import dev.stmedrano.harbor.parent.notifications.ParentNotificationState
import dev.stmedrano.harbor.parent.notifications.ParentReceipt

@Composable
fun SettingsScreen(notifications: ParentNotificationState, runtime: ParentRuntimeState, available: Boolean,
    onEnable: () -> Unit, onRemove: () -> Unit, onSignOut: () -> Unit,
    developmentReceipt: ParentReceipt? = null, onExport: (() -> Unit)? = null, onChangeRole: (() -> Unit)? = null) {
    val enabled = available && !runtime.signingOut && !notifications.busy
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HarborScreenHeader("Your account", "Settings")
        HarborPanel("Appearance") {
            Text("Night Harbor is deep water; Daybreak is the same harbor at first light.", color = harbor.ink2,
                style = MaterialTheme.typography.bodyMedium)
            HarborTabs(HarborAppearance.entries.map { option ->
                HarborTab(option.label, HarborAppearanceStore.mode == option) { HarborAppearanceStore.choose(context, option) }
            })
        }
        HarborPanel("Notifications") {
            Text(if (notifications.confirmed) "Registration confirmed" else "Registration unconfirmed", style = MaterialTheme.typography.titleSmall)
            notifications.message?.let { HarborNote(it, HarborTone.INFO) }
            HarborButton(onEnable, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Enable notifications") }
            HarborOutlinedButton(onRemove, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("Remove notification registration") }
        }
        HarborPanel("Account") {
            HarborOutlinedButton(onSignOut, enabled = available && !runtime.signingOut,
                modifier = Modifier.fillMaxWidth()) { Text("Sign out this device") }
            if (runtime.signingOut) Text("Signing out…")
            if (runtime.cleanupConfirmed) Text("Current-device cleanup confirmed.")
            if (!available) HarborNote("Sign in to manage this device. Live actions are disabled in the offline preview.", HarborTone.WARN)
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
