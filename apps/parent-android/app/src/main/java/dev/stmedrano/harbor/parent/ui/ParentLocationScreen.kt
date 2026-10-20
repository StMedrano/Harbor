package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.location.*
import kotlinx.coroutines.delay

/** Loads the parent's read-only location for one device and returns the view valid for exactly this parent and device. */
@Composable
fun rememberParentLocationView(model: ParentLocationModel?, identity: ParentIdentity, deviceId: String, resumeTick: Int = 0): LocationViewState? {
    if (model == null) return null
    LaunchedEffect(model, identity, deviceId, resumeTick) {
        while (true) { model.load(identity, deviceId); delay(60_000) }
    }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentLocationModel.Scope(identity, deviceId) }?.view
}

@Composable
fun ParentLocationScreen(view: LocationViewState, onRefresh: (() -> Unit)? = null, accent: Color = Color.Unspecified) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    HarborPanel("Location") {
        when (view.status) {
            LocationViewStatus.NONE -> Text("This phone has not shared a place yet. It starts after the child allows location in Harbor on their phone.")
            LocationViewStatus.EXPIRED -> Text("The last places this phone shared are older than 30 days and were removed.")
            LocationViewStatus.ACCESS_LOST -> HarborNote("You no longer have access to this device's location.", HarborTone.DANGER, emphasis = true)
            LocationViewStatus.LOAD_FAILED -> HarborNote("Location could not be loaded. Check your connection and try again.", HarborTone.DANGER, emphasis = true)
            LocationViewStatus.AVAILABLE -> AvailableLocation(view, now, accent.takeIf { it != Color.Unspecified } ?: MaterialTheme.colorScheme.primary)
        }
        if (view.offline) Text("Showing the last place that loaded. It could not be refreshed just now.", style = MaterialTheme.typography.bodySmall)
        if (onRefresh != null && view.status != LocationViewStatus.ACCESS_LOST) HarborSecondaryButton(onRefresh, Modifier.fillMaxWidth()) { Text("Refresh location") }
    }
}

@Composable
private fun AvailableLocation(view: LocationViewState, now: Long, accent: Color) {
    val stale = view.isStale(now)
    locationStatusMessage(view.deviceStatus)?.let { message -> HarborNote(message, HarborTone.INFO) }
    val latest = view.latest
    if (latest == null) {
        Text("No place has arrived yet.")
        return
    }
    val seen = locationInstantMs(latest.at)
    val label = "Last known place, ${locationAgeLabel(now - seen)}, accurate to about ${latest.accuracyM} metres"
    if (stale) {
        HarborNote("Nothing new has arrived from this phone for ${locationAgeLabel(now - (view.receivedAtMs ?: seen)).removeSuffix(" ago")}. The map shows its last known place, not where it is now.", HarborTone.WARN, emphasis = true)
    }
    LocationMap(view.fixes, stale, label, accent = accent)
    Text("Seen ${locationAgeLabel(now - seen)} · ${formatTimestamp(latest.at)}", style = MaterialTheme.typography.titleMedium)
    Text("Accurate to about ${latest.accuracyM} m · ${providerLabel(latest.provider)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun providerLabel(provider: LocationProvider) = when (provider) {
    LocationProvider.GPS -> "GPS"
    LocationProvider.NETWORK -> "Wi-Fi or mobile network"
    LocationProvider.FUSED -> "Combined sources"
}
