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
    val onStop: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onCheckAgain: () -> Unit,
)

/** Explicit disclosure, consent, Usage Access and stop controls. Nothing is shared until the child taps Start. */
@Composable
fun UsageSetupPanel(ui: ChildUsageUi) {
    HarborPanel("Share screen time with your parents") {
        Text("When sharing is on, Harbor reads which apps you use and for how long over the last 7 days, and the names of the apps you can open. " +
            "It sends those app names, package names and times to your parents. It does not read messages, web pages, photos, contacts or your location, and it does not limit or block any app.")
        Text("Only apps you can launch on this phone profile are listed. Your parents see the last report your phone sent, and it may be a few minutes old.",
            style = MaterialTheme.typography.bodySmall)
        if (!ui.consent) {
            Button(ui.onStart, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Start sharing screen time") }
        } else {
            Text("Sharing is on.", style = MaterialTheme.typography.titleSmall)
            if (!ui.permissionGranted) {
                Text("Usage Access is off for Harbor, so nothing can be measured yet. Turn it on in Android settings, then come back.")
                Button(ui.onOpenSettings, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Open Usage Access settings") }
                OutlinedButton(ui.onCheckAgain, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Check again") }
            }
            OutlinedButton(ui.onStop, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Stop sharing and remove my report") }
        }
    }
}
