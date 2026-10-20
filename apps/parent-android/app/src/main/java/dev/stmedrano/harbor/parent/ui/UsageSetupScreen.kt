package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.usage.UsageViewState

/** Everything the child dashboard needs to show and control screen-time sharing. Null means the feature is not offered (offline fixture). */
data class ChildUsageUi(
    val view: UsageViewState,
    val consent: Boolean,
    val permissionGranted: Boolean,
    val onStart: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onCheckAgain: () -> Unit,
)

/**
 * Screen-time sharing in one line. Sharing starts automatically once this phone is paired (Start is a fallback). Only a
 * parent can turn sharing off (by removing this device in the parent app), so there is deliberately no stop control here.
 * What is read and what is not is written out in About.
 */
@Composable
fun UsageStatusSection(ui: ChildUsageUi) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!ui.consent) {
            HarborStatus("Screen time not shared yet", ok = false)
            HarborButton(ui.onStart, Modifier.fillMaxWidth()) { Text("Start sharing screen time") }
        } else if (!ui.permissionGranted) {
            HarborStatus("Usage access off", ok = false)
            HarborButton(ui.onOpenSettings, Modifier.fillMaxWidth()) { Text("Open Usage Access settings") }
            HarborOutlinedButton(ui.onCheckAgain, Modifier.fillMaxWidth()) { Text("Check again") }
        } else HarborStatus("Sharing screen time", ok = true)
    }
}
