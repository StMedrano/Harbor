package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.DevicePublicV1

@Composable
fun DeviceScreen(device: DevicePublicV1, onRevoke: (() -> Unit)? = null, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(device.displayName, style = MaterialTheme.typography.headlineSmall)
        Text("Status: ${device.status}")
        Text("Supervision: ${device.supervisionMode}")
        Text("Last seen: ${device.lastSeenAt ?: "not reported"}")
        if (onRevoke != null && device.status == "active") OutlinedButton(onRevoke) { Text("Revoke device") }
        TextButton(onBack) { Text("Back to family") }
    }
}
