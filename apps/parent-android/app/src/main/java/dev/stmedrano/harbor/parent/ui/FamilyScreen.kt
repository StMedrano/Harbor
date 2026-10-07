package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.*
import java.time.Instant

@Composable
fun FamilyScreen(state: FamilyState, families: List<FamilyV1>, selectedChildId: String?,
    onRefresh: () -> Unit, onSelectFamily: (String) -> Unit, onSelectChild: (String) -> Unit,
    onCreateFamily: () -> Unit, onAddChild: () -> Unit, onPair: () -> Unit, onDevice: (DevicePublicV1) -> Unit) {
    val snapshot = state.snapshot
    val online = !state.cached && state.failure == null && !state.loading
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(snapshot?.family?.name ?: "Your family", style = MaterialTheme.typography.headlineSmall)
        Text("Your family and connected devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.loading) { CircularProgressIndicator(); Text("Refreshing family…") }
        if (state.cached && snapshot != null) Text("Cached view · last refreshed ${Instant.ofEpochMilli(snapshot.fetchedAt)}. Connect to make changes.", style = MaterialTheme.typography.bodySmall)
        if (state.failure != null) Text(if (state.failure == FamilyFailure.ACCESS_DENIED) "Family access was removed." else "Refresh failed. Check your connection and retry.")
        OutlinedButton(onRefresh, enabled = !state.loading, shape = MaterialTheme.shapes.medium) { Text("Refresh family") }
        if (families.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                families.forEach { family -> FilterChip(selected = snapshot?.family?.id == family.id,
                    onClick = { onSelectFamily(family.id) }, label = { Text(family.name) }) }
            }
        }
        if (snapshot == null) {
            HarborPanel("Get started") {
                Text("Create or select a family to get started.")
                Button(onCreateFamily, enabled = online, shape = MaterialTheme.shapes.medium) { Text("Create family") }
            }
        } else {
            Text("Children", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.children.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.children.forEach { child ->
                        FilterChip(selected = selectedChildId == child.id, onClick = { onSelectChild(child.id) },
                            label = { Text(child.displayName) }, shape = CircleShape,
                            border = BorderStroke(1.5.dp, if (selectedChildId == child.id) MaterialTheme.colorScheme.primary else Color.Transparent),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                selectedContainerColor = MaterialTheme.colorScheme.surface,
                                selectedLabelColor = MaterialTheme.colorScheme.primary),
                            leadingIcon = { Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(28.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(child.displayName.take(1).uppercase(), style = MaterialTheme.typography.labelMedium) }
                            } })
                    }
                }
            }
            if (snapshot.children.isEmpty()) HarborPanel("Add your first child") { Text("Add a child, then pair their Android device.") }
            snapshot.children.forEach { child ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = BorderStroke(1.dp, if (selectedChildId == child.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(child.displayName, style = MaterialTheme.typography.titleMedium)
                        TextButton({ onSelectChild(child.id) }) { Text(if (selectedChildId == child.id) "Selected child" else "View child") }
                        snapshot.devices.filter { it.childId == child.id }.forEach { device ->
                            TextButton({ onDevice(device) }) { Text("${device.displayName} · ${device.status} · ${device.supervisionMode}") }
                        }
                        if (snapshot.devices.none { it.childId == child.id }) Text("No enrolled device reported.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onAddChild, enabled = online, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Add child") }
            OutlinedButton(onPair, enabled = online && selectedChildId != null, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Pair device") }
            TextButton(onCreateFamily, enabled = online) { Text("Create family") }
        }
    }
}
