package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
 * One short status line and, only when something needs doing, the action for it. Sharing starts by itself once Android's
 * permission is given. Only a parent can turn it off (by removing this device in the parent app), so there is
 * deliberately no stop control here. The full explanation lives in About.
 */
@Composable
fun LocationStatusSection(ui: ChildLocationUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (locationStatus(ui.access)) {
            LocationStatus.PERMISSION_LOST -> {
                HarborStatus("Location off", ok = false, glyph = HarborGlyph.MAP)
                HarborButton(ui.onAllowLocation, Modifier.fillMaxWidth()) { Text("Allow location") }
                HarborOutlinedButton(ui.onOpenAppSettings, Modifier.fillMaxWidth()) { Text("Open app settings") }
            }
            LocationStatus.SERVICES_OFF -> {
                HarborStatus("Location is off in Android settings", ok = false, glyph = HarborGlyph.MAP)
                HarborButton(ui.onOpenLocationSettings, Modifier.fillMaxWidth()) { Text("Open location settings") }
            }
            LocationStatus.BACKGROUND_MISSING -> {
                HarborStatus("Sharing only while Harbor is open", ok = false, glyph = HarborGlyph.MAP)
                HarborButton(ui.onAllowAllTheTime, Modifier.fillMaxWidth()) { Text("Allow all the time") }
                HarborOutlinedButton(ui.onOpenAppSettings, Modifier.fillMaxWidth()) { Text("Open app settings") }
            }
            LocationStatus.SHARING -> HarborStatus("Sharing location", ok = true, glyph = HarborGlyph.MAP,
                detail = if (ui.host.uploadFailing) "Can't reach Harbor. Trying again."
                    else ui.host.lastUploadMs?.let { "Last sent ${formatTimestamp(java.time.Instant.ofEpochMilli(it).toString())}" })
        }
    }
}
