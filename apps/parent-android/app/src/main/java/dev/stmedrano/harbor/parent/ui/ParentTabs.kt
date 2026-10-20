package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.alerts.AlertsViewState
import dev.stmedrano.harbor.parent.checkin.CheckInViewState
import dev.stmedrano.harbor.parent.family.ChildV1
import dev.stmedrano.harbor.parent.family.DevicePublicV1
import dev.stmedrano.harbor.parent.location.LocationViewState
import dev.stmedrano.harbor.parent.usage.UsageViewState

/**
 * The parent app's four pages, like the web app's tab bar (Map, Alerts, Activity, Family). The web app's fifth tab,
 * Controls, is not here: Harbor does not block, limit or filter anything.
 */
enum class ParentTab(val label: String, val glyph: HarborGlyph) {
    MAP("Map", HarborGlyph.MAP),
    ALERTS("Alerts", HarborGlyph.ALERTS),
    ACTIVITY("Activity", HarborGlyph.ACTIVITY),
    FAMILY("Family", HarborGlyph.FAMILY),
}

/** Like the web app: open on the map once a child exists, otherwise on Family where a child is added. */
fun defaultParentTab(hasChildren: Boolean): ParentTab = if (hasChildren) ParentTab.MAP else ParentTab.FAMILY

fun parentNavItems(selected: ParentTab, onSelect: (ParentTab) -> Unit): List<HarborNavItem> =
    ParentTab.values().map { tab -> HarborNavItem(tab.label, tab.glyph, tab == selected) { onSelect(tab) } }

/** The device a page should read: the child's one active phone, else the newest remaining one. Revoked phones never count. */
fun activeDevice(devices: List<DevicePublicV1>, childId: String): DevicePublicV1? {
    val mine = devices.filter { it.childId == childId && it.status != "revoked" }
    return mine.firstOrNull { it.status == "active" } ?: mine.firstOrNull()
}

/** Child switcher shown at the top of every page: an avatar and a name per child, and "+ Add child". */
@Composable
fun ChildChips(children: List<ChildV1>, selectedId: String?, onSelect: (String) -> Unit, onAdd: (() -> Unit)?, addEnabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        children.forEachIndexed { index, child ->
            val selected = selectedId == child.id
            FilterChip(selected = selected, onClick = { onSelect(child.id) },
                label = { Text(child.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) }, shape = CircleShape,
                border = BorderStroke(1.5.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    selectedContainerColor = MaterialTheme.colorScheme.surface,
                    selectedLabelColor = MaterialTheme.colorScheme.primary),
                leadingIcon = { HarborAvatar(child.displayName, 30.dp, HarborKid.color(index)) })
        }
        if (onAdd != null) FilterChip(selected = false, onClick = onAdd, enabled = addEnabled, shape = CircleShape,
            label = { Text("+ Add child", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium) },
            border = BorderStroke(1.5.dp, Color.Transparent),
            colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest))
    }
}

/** Everything one page reads for the selected child's phone. Each part is null when its feature is not running. */
data class ParentChildData(
    val location: LocationViewState? = null, val onRefreshLocation: (() -> Unit)? = null,
    val alerts: AlertsViewState? = null, val onRefreshAlerts: (() -> Unit)? = null,
    val usage: UsageViewState? = null, val onRefreshUsage: (() -> Unit)? = null,
    val checkIn: CheckInViewState? = null, val onAskCheckIn: (() -> Unit)? = null,
)

/** One tab's page for one child. Detail text lives in About and the policies; the page shows only what the phone reported. */
@Composable
fun ParentChildPage(tab: ParentTab, child: ChildV1, device: DevicePublicV1?, data: ParentChildData, accent: Color, onPair: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
        Text(tab.label, style = MaterialTheme.typography.headlineSmall)
        if (device == null) {
            HarborPanel("No phone yet") {
                Text("${child.displayName} has no phone connected.")
                if (onPair != null) HarborButton(onPair, Modifier.fillMaxWidth()) { Text("Pair device") }
            }
            return
        }
        when (tab) {
            ParentTab.MAP -> {
                data.location?.let { ParentLocationScreen(it, data.onRefreshLocation, accent) }
                if (data.checkIn != null && device.status == "active") ParentCheckInScreen(data.checkIn, child.displayName, data.onAskCheckIn)
            }
            ParentTab.ALERTS -> data.alerts?.let { ParentAlertsScreen(it, data.onRefreshAlerts) }
            ParentTab.ACTIVITY -> data.usage?.let { UsageReportScreen(it, data.onRefreshUsage) }
            ParentTab.FAMILY -> Unit
        }
    }
}
