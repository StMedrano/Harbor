package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.location.*
import dev.stmedrano.harbor.parent.profile.ProfileLease
import kotlinx.coroutines.delay

/** What the child dashboard needs to explain and fix location sharing. Null means the feature is not offered (offline fixture). */
data class ChildLocationUi(
    val access: LocationAccess,
    val host: LocationHostState,
    val onAllowLocation: () -> Unit,
    val onAllowAllTheTime: () -> Unit,
    val onOpenLocationSettings: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

/** Re-reads the phone's real permission state on resume and every few seconds while visible; it never trusts a cached answer. */
@Composable
fun rememberChildLocationUi(graph: ParentApplication, lease: ProfileLease, resumeTick: Int, onAllowLocation: () -> Unit,
    onAllowAllTheTime: () -> Unit, onOpenLocationSettings: () -> Unit, onOpenAppSettings: () -> Unit): ChildLocationUi {
    var access by remember(lease) { mutableStateOf(graph.locationAccess()) }
    val host = graph.childLocationHost?.state?.collectAsState()?.value ?: LocationHostState()
    LaunchedEffect(lease, resumeTick) {
        while (true) {
            access = graph.locationAccess()
            delay(3000)
        }
    }
    LaunchedEffect(lease, resumeTick) { graph.refreshChildLocation(lease) }
    return ChildLocationUi(access, host, onAllowLocation, onAllowAllTheTime, onOpenLocationSettings, onOpenAppSettings)
}

/**
 * Plain disclosure and the steps a child can take. Sharing starts by itself once Android's permission is given. Only a
 * parent can turn it off (by removing this device in the parent app), so there is deliberately no stop control here.
 */
@Composable
fun LocationSetupPanel(ui: ChildLocationUi) {
    HarborPanel("Share your location with your parents") {
        Text("Harbor sends this phone's location to your parents about every 5 minutes while moving or 15 minutes while still, when Android allows: latitude, longitude, accuracy and time. Updates may be delayed by location permissions or battery settings. " +
            "It keeps up to 30 days of recent places for them. A notification stays visible while location is being shared. " +
            "Only your parents can turn sharing off, by removing this device in the parent app.")
        when (locationStatus(ui.access)) {
            LocationStatus.PERMISSION_LOST -> {
                HarborNote("Location is not allowed for Harbor yet, so your parents cannot see where this phone is.", HarborTone.WARN, emphasis = true)
                HarborButton(ui.onAllowLocation, Modifier.fillMaxWidth()) { Text("Allow location") }
                HarborOutlinedButton(ui.onOpenAppSettings, Modifier.fillMaxWidth()) { Text("Open app settings") }
            }
            LocationStatus.SERVICES_OFF -> {
                HarborNote("Location is switched off on this phone. Turn it on in Android settings.", HarborTone.WARN, emphasis = true)
                HarborButton(ui.onOpenLocationSettings, Modifier.fillMaxWidth()) { Text("Open location settings") }
            }
            LocationStatus.BACKGROUND_MISSING -> {
                HarborNote("Sharing works while Harbor is open. Allow location \"all the time\" so it keeps working when Harbor is closed.", HarborTone.WARN, emphasis = true)
                HarborButton(ui.onAllowAllTheTime, Modifier.fillMaxWidth()) { Text("Allow all the time") }
                HarborOutlinedButton(ui.onOpenAppSettings, Modifier.fillMaxWidth()) { Text("Open app settings") }
            }
            LocationStatus.SHARING -> {
                Text("Sharing your location.", style = MaterialTheme.typography.titleSmall)
                if (ui.host.uploadFailing) Text("Your phone could not reach Harbor just now. It will try again.", style = MaterialTheme.typography.bodySmall)
                else ui.host.lastUploadMs?.let { Text("Last sent: ${formatTimestamp(java.time.Instant.ofEpochMilli(it).toString())}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
