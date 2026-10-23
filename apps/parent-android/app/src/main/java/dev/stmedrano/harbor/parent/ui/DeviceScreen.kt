package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.alerts.AlertsViewState
import dev.stmedrano.harbor.parent.checkin.CheckInViewState
import dev.stmedrano.harbor.parent.family.DevicePublicV1
import dev.stmedrano.harbor.parent.location.LocationViewState
import dev.stmedrano.harbor.parent.usage.UsageViewState

@Composable
fun DeviceScreen(device: DevicePublicV1, onRevoke: (() -> Unit)? = null, cachedAt: Long? = null, usage: UsageViewState? = null,
    onRefreshUsage: (() -> Unit)? = null, location: LocationViewState? = null, onRefreshLocation: (() -> Unit)? = null,
    alerts: AlertsViewState? = null, onRefreshAlerts: (() -> Unit)? = null,
    checkIn: CheckInViewState? = null, childName: String = "your child", onAskCheckIn: (() -> Unit)? = null,
    accent: Color = Color.Unspecified, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        HarborBackButton(onBack, "Back to family")
        HarborScreenHeader("$childName's phone", device.displayName)
        cachedAt?.let { HarborNote("Cached view · last refreshed ${java.time.Instant.ofEpochMilli(it)}. Connect to make changes.", HarborTone.WARN) }
        HarborPanel("Connected device") {
            HarborRow("Status: ${device.status}", leading = { HarborIcon(HarborGlyph.PHONE, harbor.ink2) })
            HarborRow("Supervision: ${device.supervisionMode}", divider = true, leading = { HarborIcon(HarborGlyph.EYE, harbor.ink2) })
            HarborRow("Last seen: ${device.lastSeenAt?.let(::formatTimestamp) ?: "not reported"}", divider = true,
                leading = { HarborIcon(HarborGlyph.HOURGLASS, harbor.ink2) })
        }
        if (location != null) ParentLocationScreen(location, onRefreshLocation, accent)
        if (alerts != null) ParentAlertsScreen(alerts, onRefreshAlerts)
        if (checkIn != null && device.status == "active") ParentCheckInScreen(checkIn, childName, onAskCheckIn)
        if (usage != null) UsageReportScreen(usage, onRefreshUsage)
        if (onRevoke != null && device.status == "active") HarborDestructiveButton(onRevoke, Modifier.fillMaxWidth()) { Text("Revoke device") }
    }
}
