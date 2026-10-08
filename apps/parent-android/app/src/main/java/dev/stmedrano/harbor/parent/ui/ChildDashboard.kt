package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildSyncState

@Composable fun ChildDashboard(state: ChildSyncState, binding: ChildBinding, onSync: () -> Unit, onRequestRoleChange: () -> Unit,
    notificationConfirmed: Boolean = false, onEnableNotifications: (() -> Unit)? = null) {
    if (state is ChildSyncState.Blocked) {
        Text("This phone needs a parent to check its setup.")
        return
    }
    var tab by remember(binding.deviceId) { mutableStateOf("Today") }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your phone", style = MaterialTheme.typography.headlineMedium)
        Row {
            listOf("Today", "Apps", "About").forEach { title ->
                TextButton(onClick = { tab = title }) { Text(title) }
            }
        }
        when (tab) {
            "Today" -> {
                Text("Connected to your family")
                when (state) {
                    is ChildSyncState.Fresh -> {
                        Text("Signed sync confirmed. State version: ${state.desiredVersion}.")
                        Text("Last sync: ${java.time.Instant.ofEpochSecond(state.receivedAt)}")
                    }
                    is ChildSyncState.Stale -> {
                        Text("Showing saved setup. Current status has not been confirmed.")
                        state.lastSuccessAt?.let { Text("Last confirmed sync: ${java.time.Instant.ofEpochSecond(it)}") }
                    }
                    is ChildSyncState.Blocked -> Unit
                }
                Button(onClick = onSync) { Text("Sync now") }
            }
            "Apps" -> Text("App information is not available yet.")
            "About" -> {
                Text("Harbor stores this phone’s pairing credentials securely and checks its family connection with signed requests.")
                Text("Screen time and app enforcement are not available in this build.")
                if (onEnableNotifications != null) {
                    Text(if (notificationConfirmed) "Backend notification registration confirmed. Delivery still requires receipt evidence."
                        else "Backend notification registration is unconfirmed.")
                    Button(onClick = onEnableNotifications) { Text("Enable notifications") }
                }
                OutlinedButton(onClick = onRequestRoleChange) { Text("Ask a parent to change setup") }
            }
        }
    }
}
