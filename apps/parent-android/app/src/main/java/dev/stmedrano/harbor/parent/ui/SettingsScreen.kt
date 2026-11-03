package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentRuntimeState
import dev.stmedrano.harbor.parent.notifications.ParentNotificationState
import dev.stmedrano.harbor.parent.notifications.ParentReceipt

/** Deleting the account, which Google Play requires inside the app. It says exactly what goes, and needs the word typed. */
@Composable
private fun DeleteAccountRow(enabled: Boolean, onDelete: suspend (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    HarborDestructiveButton({ open = true; typed = ""; failed = false }, Modifier.fillMaxWidth(), enabled = enabled) { Text("Delete my account") }
    if (open) AlertDialog(onDismissRequest = { if (!busy) open = false }, containerColor = harbor.s2,
        title = { Text("Delete your account?", style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("This permanently deletes your account and sign-in. A family with another parent stays with them, and you leave it.", color = harbor.ink2)
                Text("Any family where you are the only parent is deleted too: its children, their phones, and every place, alert and report those phones sent. Those phones stop sharing.", color = harbor.ink2)
                Text("This can't be undone.", color = harbor.ink2)
                HarborField(typed, { typed = it }, "Type DELETE to confirm", enabled = !busy)
                if (failed) HarborNote("Couldn't delete your account. Check your connection and try again.", HarborTone.DANGER, emphasis = true)
                HarborDestructiveButton({
                    busy = true; failed = false
                    scope.launch {
                        try { onDelete(typed.trim()); open = false }
                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { busy = false }
                    }
                }, Modifier.fillMaxWidth(), enabled = !busy && typed.trim() == "DELETE") { Text("Delete my account") }
            }
        }, confirmButton = { TextButton({ open = false }, enabled = !busy) { Text("Cancel") } })
}

@Composable
fun SettingsScreen(notifications: ParentNotificationState, runtime: ParentRuntimeState, available: Boolean,
    onEnable: () -> Unit, onRemove: () -> Unit, onSignOut: () -> Unit,
    developmentReceipt: ParentReceipt? = null, onExport: (() -> Unit)? = null, onChangeRole: (() -> Unit)? = null,
    onDeleteAccount: (suspend (String) -> Unit)? = null) {
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
            onDeleteAccount?.let { delete -> DeleteAccountRow(available && !runtime.signingOut, delete) }
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
