package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.*
import java.time.Instant

@Composable
fun FamilyScreen(state: FamilyState, families: List<FamilyV1>, selectedChildId: String?,
    onRefresh: () -> Unit, onSelectFamily: (String) -> Unit, onSelectChild: (String) -> Unit,
    onCreateFamily: () -> Unit, onAddChild: () -> Unit, onPair: () -> Unit, onDevice: (DevicePublicV1) -> Unit) {
    val snapshot = state.snapshot
    val online = !state.cached && state.failure == null && !state.loading
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(snapshot?.family?.name ?: "Your family", style = MaterialTheme.typography.headlineSmall)
        if (state.loading) { CircularProgressIndicator(); Text("Refreshing family…") }
        if (state.cached && snapshot != null) Text("Cached view · last refreshed ${Instant.ofEpochMilli(snapshot.fetchedAt)}. Connect to make changes.")
        if (state.failure != null) Text(if (state.failure == FamilyFailure.ACCESS_DENIED) "Family access was removed." else "Refresh failed. Check your connection and retry.")
        OutlinedButton(onRefresh, enabled = !state.loading) { Text("Refresh family") }
        if (families.size > 1) families.forEach { family -> TextButton({ onSelectFamily(family.id) }) { Text(family.name) } }
        OutlinedButton(onCreateFamily, enabled = online) { Text("Create family") }
        if (snapshot == null) Text("Create or select a family to get started.") else {
            if (snapshot.children.isEmpty()) Text("No children yet.")
            snapshot.children.forEach { child ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(child.displayName, style = MaterialTheme.typography.titleLarge)
                        TextButton({ onSelectChild(child.id) }) { Text(if (selectedChildId == child.id) "Selected child" else "View child") }
                        snapshot.devices.filter { it.childId == child.id }.forEach { device ->
                            TextButton({ onDevice(device) }) { Text("${device.displayName} · ${device.status} · ${device.supervisionMode}") }
                        }
                        if (snapshot.devices.none { it.childId == child.id }) Text("No enrolled device reported.")
                    }
                }
            }
            Button(onAddChild, enabled = online) { Text("Add child") }
            OutlinedButton(onPair, enabled = online && selectedChildId != null) { Text("Pair device") }
        }
    }
}
