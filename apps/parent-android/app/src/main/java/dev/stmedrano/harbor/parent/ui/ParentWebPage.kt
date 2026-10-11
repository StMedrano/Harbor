package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import dev.stmedrano.harbor.parent.location.ParentLocationModel
import dev.stmedrano.harbor.parent.usage.ParentUsageModel

/**
 * The five navigation destinations and line-icon order match the responsive Harbor Family web parent shell.
 * Native capabilities must be backed by verified services; never reuse the web demo's synthetic controls,
 * alerts, check-ins, calls or history as if they were measured by the child's phone.
 */
fun parentWebNavItems(selected: String, onSelect: (String) -> Unit) = listOf(
    HarborNavItem("Map", HarborGlyph.MAP, selected == "Map") { onSelect("Map") },
    HarborNavItem("Alerts", HarborGlyph.ALERTS, selected == "Alerts") { onSelect("Alerts") },
    HarborNavItem("Controls", HarborGlyph.CONTROLS, selected == "Controls") { onSelect("Controls") },
    HarborNavItem("Activity", HarborGlyph.ACTIVITY, selected == "Activity") { onSelect("Activity") },
    HarborNavItem("Family", HarborGlyph.FAMILY, selected == "Family") { onSelect("Family") },
)

/** Native, read-only parent tabs backed by this family's actual paired children and devices. */
@Composable
fun ParentWebPage(
    tab: String,
    snapshot: FamilySnapshot?,
    identity: ParentIdentity,
    locationModel: ParentLocationModel?,
    usageModel: ParentUsageModel?,
    resumeTick: Int = 0,
) {
    // Reject a snapshot from any other identity before selecting or reading a device.
    val family = snapshot?.takeIf { it.membership.userId == identity.userId && it.membership.status == "active" }
    var chosenChildId by remember(identity, family?.family?.id) { mutableStateOf<String?>(null) }
    val child = family?.children?.firstOrNull { it.id == chosenChildId } ?: family?.children?.firstOrNull()
    val device = family?.devices?.firstOrNull { it.childId == child?.id && it.status == "active" }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (family?.children?.isNotEmpty() == true) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                family.children.forEach { kid ->
                    FilterChip(
                        selected = child?.id == kid.id,
                        onClick = { chosenChildId = kid.id },
                        label = { Text(kid.displayName) },
                        leadingIcon = { HarborAvatar(kid.displayName, 28.dp) },
                        shape = CircleShape,
                        border = BorderStroke(1.5.dp, if (child?.id == kid.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            selectedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        }
        when (tab) {
            "Map" -> {
                Text("Location", style = MaterialTheme.typography.headlineSmall)
                if (child == null) HarborCard { Text("Add a child and pair their phone to see its last reported location.") }
                else if (device == null) HarborCard { Text("Pair ${child.displayName}'s phone to see their last known location.") }
                else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        HarborAvatar(child.displayName, 46.dp)
                        Column {
                            Text(child.displayName, style = MaterialTheme.typography.titleMedium)
                            Text("Last confirmed device location · not live tracking", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    val view = rememberParentLocationView(locationModel, identity, device.id, resumeTick)
                    if (view == null) HarborCard { Text("Loading the last confirmed location…") }
                    else ParentLocationScreen(view)
                }
            }
            "Activity" -> {
                Text("Activity", style = MaterialTheme.typography.headlineSmall)
                if (child == null) HarborCard { Text("Add a child and pair their phone to view reported screen time.") }
                else if (device == null) HarborCard { Text("Pair ${child.displayName}'s phone to see their screen-time report.") }
                else {
                    val view = rememberParentUsageView(usageModel, identity, device.id, resumeTick)
                    if (view == null) HarborCard { Text("Loading the last confirmed screen-time report…") }
                    else UsageReportScreen(view)
                }
            }
            "Alerts" -> {
                Text("Alerts", style = MaterialTheme.typography.headlineSmall)
                HarborCard {
                    Text("Message alerts are not available in this Android build yet.", style = MaterialTheme.typography.titleMedium)
                    Text("No monitoring result or safety status can be inferred until the child-phone alert feature is implemented and verified.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            "Controls" -> {
                Text("Controls", style = MaterialTheme.typography.headlineSmall)
                HarborCard {
                    Text("App limits and phone pausing are not available yet.", style = MaterialTheme.typography.titleMedium)
                    Text("Harbor can report screen time, but does not currently enforce schedules, block apps, or pause a device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
