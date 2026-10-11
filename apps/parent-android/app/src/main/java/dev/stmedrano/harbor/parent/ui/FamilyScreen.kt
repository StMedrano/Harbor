package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
        if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp); Text("Refreshing family…")
        }
        if (state.cached && snapshot != null) HarborNote("Cached view · last refreshed ${Instant.ofEpochMilli(snapshot.fetchedAt)}. Connect to make changes.", HarborTone.WARN)
        if (state.failure != null) HarborNote(if (state.failure == FamilyFailure.ACCESS_DENIED) "Family access was removed." else "Refresh failed. Check your connection and retry.", HarborTone.DANGER, emphasis = true)
        HarborSecondaryButton(onRefresh, enabled = !state.loading) { Text("Refresh family") }
        if (families.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                families.forEach { family -> FilterChip(selected = snapshot?.family?.id == family.id,
                    onClick = { onSelectFamily(family.id) }, label = { Text(family.name) }) }
            }
        }
        if (snapshot == null) {
            HarborPanel("Get started") {
                Text("Create or select a family to get started.")
                HarborButton(onCreateFamily, enabled = online) { Text("Create family") }
            }
        } else {
            Text("Children", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (snapshot.children.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.children.forEachIndexed { index, child ->
                        FilterChip(selected = selectedChildId == child.id, onClick = { onSelectChild(child.id) },
                            label = { Text(child.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }, shape = CircleShape,
                            border = BorderStroke(1.5.dp, if (selectedChildId == child.id) MaterialTheme.colorScheme.primary else Color.Transparent),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                selectedContainerColor = MaterialTheme.colorScheme.surface,
                                selectedLabelColor = MaterialTheme.colorScheme.primary),
                            leadingIcon = { HarborAvatar(child.displayName, 30.dp, HarborKid.color(index)) })
                    }
                }
            }
            if (snapshot.children.isEmpty()) HarborPanel("Add your first child") { Text("Add a child, then pair their Android device.") }
            snapshot.children.forEachIndexed { index, child ->
                Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    border = if (selectedChildId == child.id) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HarborAvatar(child.displayName, 46.dp, HarborKid.color(index))
                            Text(child.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton({ onSelectChild(child.id) }) { Text(if (selectedChildId == child.id) "Selected child" else "View child") }
                        }
                        snapshot.devices.filter { it.childId == child.id && it.status != "revoked" }.forEach { device ->
                            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surface)
                                .clickable(role = Role.Button) { onDevice(device) }
                                .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${device.displayName} · ${device.status} · ${device.supervisionMode}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        if (snapshot.devices.none { it.childId == child.id && it.status != "revoked" }) Text("No enrolled device reported.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HarborButton(onAddChild, enabled = online, modifier = Modifier.fillMaxWidth()) { Text("Add child") }
            HarborOutlinedButton(onPair, enabled = online && selectedChildId != null, modifier = Modifier.fillMaxWidth()) { Text("Pair device") }
            TextButton(onCreateFamily, enabled = online) { Text("Create family") }
        }
    }
}
