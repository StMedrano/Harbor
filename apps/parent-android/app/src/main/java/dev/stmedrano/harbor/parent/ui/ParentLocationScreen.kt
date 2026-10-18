package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
fun ParentLocationScreen(view: LocationViewState, onRefresh: (() -> Unit)? = null) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    HarborPanel("Location") {
        when (view.status) {
            LocationViewStatus.NONE -> Text("This phone has not shared a place yet. It starts after the child allows location in Harbor on their phone.")
            LocationViewStatus.EXPIRED -> Text("The last places this phone shared are older than 30 days and were removed.")
            LocationViewStatus.ACCESS_LOST -> Text("You no longer have access to this device's location.")
            LocationViewStatus.LOAD_FAILED -> Text("Location could not be loaded. Check your connection and try again.")
            LocationViewStatus.AVAILABLE -> AvailableLocation(view, now)
        }
        if (view.offline) Text("Showing the last place that loaded. It could not be refreshed just now.", style = MaterialTheme.typography.bodySmall)
        if (onRefresh != null && view.status != LocationViewStatus.ACCESS_LOST) HarborOutlinedButton(onRefresh, Modifier.fillMaxWidth()) { Text("Refresh location") }
    }
}

@Composable
private fun AvailableLocation(view: LocationViewState, now: Long) {
    val stale = view.isStale(now)
    locationStatusMessage(view.deviceStatus)?.let { message ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    val latest = view.latest
    if (latest == null) {
        Text("No place has arrived yet.")
        return
    }
    val seen = locationInstantMs(latest.at)
    val label = "Last known place, ${locationAgeLabel(now - seen)}, accurate to about ${latest.accuracyM} metres"
    if (stale) {
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Text("Nothing new has arrived from this phone for ${locationAgeLabel(now - (view.receivedAtMs ?: seen)).removeSuffix(" ago")}. The map shows its last known place, not where it is now.",
                Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
    LocationMap(view.fixes, stale, label)
    Text("Seen ${locationAgeLabel(now - seen)} · ${formatTimestamp(latest.at)}", style = MaterialTheme.typography.titleSmall)
    Text("Accurate to about ${latest.accuracyM} m · ${providerLabel(latest.provider)}", style = MaterialTheme.typography.bodySmall)
}

private fun providerLabel(provider: LocationProvider) = when (provider) {
    LocationProvider.GPS -> "GPS"
    LocationProvider.NETWORK -> "Wi-Fi or mobile network"
    LocationProvider.FUSED -> "Combined sources"
}
