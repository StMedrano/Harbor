package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.DevicePublicV1
import dev.stmedrano.harbor.parent.usage.UsageViewState

@Composable
fun DeviceScreen(device: DevicePublicV1, onRevoke: (() -> Unit)? = null, cachedAt: Long? = null, usage: UsageViewState? = null,
    onRefreshUsage: (() -> Unit)? = null, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(device.displayName, style = MaterialTheme.typography.headlineSmall)
        cachedAt?.let { Text("Cached view · last refreshed ${java.time.Instant.ofEpochMilli(it)}. Connect to make changes.", style = MaterialTheme.typography.bodySmall) }
        HarborPanel("Connected device") {
            Text("Status: ${device.status}")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text("Supervision: ${device.supervisionMode}")
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Text("Last seen: ${device.lastSeenAt?.let(::formatTimestamp) ?: "not reported"}")
        }
        if (usage != null) UsageReportScreen(usage, onRefreshUsage)
        if (onRevoke != null && device.status == "active") OutlinedButton(onRevoke, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Revoke device") }
        TextButton(onBack) { Text("Back to family") }
    }
}
