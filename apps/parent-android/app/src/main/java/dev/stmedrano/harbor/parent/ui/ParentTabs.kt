package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import dev.stmedrano.harbor.parent.alerts.AlertsViewState
import dev.stmedrano.harbor.parent.checkin.CheckInViewState
import dev.stmedrano.harbor.parent.family.ChildV1
import dev.stmedrano.harbor.parent.family.DevicePublicV1
import dev.stmedrano.harbor.parent.location.LocationViewState
import dev.stmedrano.harbor.parent.usage.UsageViewState

/**
 * The parent app's five pages: Map, Alerts, Controls, Activity, Family. Each opens with an eyebrow and a title in
 * Harbor's voice; Map opens with a greeting instead ([title] null).
 */
enum class ParentTab(val label: String, val glyph: HarborGlyph, val eyebrow: String, val title: String?) {
    MAP("Map", HarborGlyph.MAP, "Family overview", null),
    ALERTS("Alerts", HarborGlyph.ALERTS, "What needs attention", "Beacon alerts"),
    CONTROLS("Controls", HarborGlyph.CONTROLS, "Family boundaries", "Steady guidance"),
    ACTIVITY("Activity", HarborGlyph.ACTIVITY, "Daily patterns", "A day at a glance"),
    FAMILY("Family", HarborGlyph.FAMILY, "Your circle", "Your crew"),
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

/** The page header for [tab]: its eyebrow and title, or a greeting on Map. */
@Composable
fun ParentTabHeader(tab: ParentTab, subtitle: String? = null) =
    HarborScreenHeader(tab.eyebrow, tab.title ?: rememberGreeting(GreetingAudience.PARENT), subtitle = subtitle)

/**
 * The child switcher at the top of every page (design system `KidPill` stack): overlapping crew-colour avatars, the
 * selected child in front with a brand ring, the child's name beside the stack, and a dashed "add" button at the end.
 */
@Composable
fun KidSwitcher(children: List<ChildV1>, selectedId: String?, onSelect: (String) -> Unit, onAdd: (() -> Unit)?, addEnabled: Boolean = true,
    meta: String? = null) {
    val c = harbor
    val selected = children.firstOrNull { it.id == selectedId }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (children.isNotEmpty()) Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy((-12).dp)) {
            children.forEachIndexed { index, child ->
                val on = child.id == selectedId
                Box(Modifier.zIndex(if (on) children.size + 1f else (children.size - index).toFloat())
                    .scale(if (on) 1.06f else 1f)
                    .size(44.dp).clip(CircleShape)
                    .selectable(selected = on, role = Role.Tab) { onSelect(child.id) }
                    .semantics { contentDescription = child.displayName }
                    .then(if (on) Modifier.border(2.dp, c.brand, CircleShape).padding(2.dp) else Modifier)
                    .background(c.s, CircleShape).padding(3.dp).background(HarborKid.color(index), CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(child.displayName.trim().take(1).uppercase(), Modifier.clearAndSetSemantics {}, color = c.onColor,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
        Column(Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }) {
            if (selected != null) Text(selected.displayName, style = MaterialTheme.typography.titleMedium, color = c.ink)
            if (selected != null && meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = c.ink2)
        }
        if (onAdd != null) Box(Modifier.size(44.dp).alpha(if (addEnabled) 1f else 0.45f).clip(CircleShape)
            .drawBehind {
                val width = 1.5.dp.toPx()
                drawCircle(c.line, radius = size.minDimension / 2 - width / 2,
                    style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
            }
            .clickable(enabled = addEnabled, role = Role.Button, onClick = onAdd)
            .semantics { contentDescription = "Add child" }, contentAlignment = Alignment.Center) {
            HarborIcon(HarborGlyph.PLUS, c.ink2, Modifier.size(20.dp))
        }
    }
}

/** Everything one page reads for the selected child's phone. Each part is null when its feature is not running. */
data class ParentChildData(
    val location: LocationViewState? = null, val onRefreshLocation: (() -> Unit)? = null,
    val alerts: AlertsViewState? = null, val onRefreshAlerts: (() -> Unit)? = null,
    val usage: UsageViewState? = null, val onRefreshUsage: (() -> Unit)? = null,
    val checkIn: CheckInViewState? = null, val onAskCheckIn: (() -> Unit)? = null,
    val controls: dev.stmedrano.harbor.parent.controls.ControlsViewState? = null,
    /** Applies one change to the latest controls and writes it straight away; null while offline. */
    val onChangeControls: (((dev.stmedrano.harbor.parent.controls.ControlsV1) -> dev.stmedrano.harbor.parent.controls.ControlsV1) -> Unit)? = null,
    val onRefreshControls: (() -> Unit)? = null,
    val accessRequests: dev.stmedrano.harbor.parent.access.AccessViewState? = null,
    val onAnswerAccess: ((dev.stmedrano.harbor.parent.access.AccessRequestItem, Boolean) -> Unit)? = null,
    val activity: dev.stmedrano.harbor.parent.activity.ActivityViewState? = null, val activityToday: java.time.LocalDate = java.time.LocalDate.now(),
    val onActivityDay: (java.time.LocalDate) -> Unit = {}, val onRefreshActivity: (() -> Unit)? = null,
)

/** One tab's page for one child. Detail text lives in About and the policies; the page shows only what the phone reported. */
@Composable
fun ParentChildPage(tab: ParentTab, child: ChildV1, device: DevicePublicV1?, data: ParentChildData, accent: Color, onPair: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        ParentTabHeader(tab, subtitle = device?.let { "${child.displayName}'s ${it.displayName}" } ?: child.displayName)
        if (device == null) {
            HarborCard {
                HarborEmptyState("No phone yet", art = HarborArt.ANCHOR, body = "${child.displayName} has no phone connected.",
                    action = if (onPair != null) "Pair device" else null, onAction = onPair)
            }
            return
        }
        when (tab) {
            ParentTab.MAP -> {
                data.location?.let { ParentLocationScreen(it, data.onRefreshLocation, accent) }
                if (data.checkIn != null && device.status == "active") ParentCheckInScreen(data.checkIn, child.displayName, data.onAskCheckIn)
            }
            ParentTab.ALERTS -> data.alerts?.let { ParentAlertsScreen(it, data.onRefreshAlerts) }
            ParentTab.CONTROLS -> data.controls?.let {
                ParentControlsScreen(it, child.displayName, data.usage, data.activity?.controlsOn, data.onChangeControls, data.onRefreshControls,
                    data.accessRequests, data.onAnswerAccess, data.activity)
            }
            ParentTab.ACTIVITY -> {
                data.activity?.let { ParentActivityScreen(it, data.activityToday, data.onActivityDay, data.onRefreshActivity) }
                data.usage?.let { UsageReportScreen(it, data.onRefreshUsage) }
            }
            ParentTab.FAMILY -> Unit
        }
    }
}
