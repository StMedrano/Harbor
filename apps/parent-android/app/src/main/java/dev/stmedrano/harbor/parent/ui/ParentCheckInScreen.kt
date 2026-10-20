package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.checkin.CheckInEntry
import dev.stmedrano.harbor.parent.checkin.CheckInPreset
import dev.stmedrano.harbor.parent.checkin.CheckInStatus
import dev.stmedrano.harbor.parent.checkin.CheckInViewState
import dev.stmedrano.harbor.parent.checkin.CheckInViewStatus
import dev.stmedrano.harbor.parent.checkin.ParentCheckInModel
import dev.stmedrano.harbor.parent.location.locationInstantMs
import kotlinx.coroutines.delay

/** Loads check-ins for one device and returns the view valid for exactly this parent and device. */
@Composable
fun rememberParentCheckInView(model: ParentCheckInModel?, identity: ParentIdentity, deviceId: String, resumeTick: Int = 0): CheckInViewState? {
    if (model == null) return null
    LaunchedEffect(model, identity, deviceId, resumeTick) {
        while (true) { model.load(identity, deviceId); delay(30_000) }
    }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentCheckInModel.Scope(identity, deviceId) }?.view
}

/** A parent's section on the device page: ask, see whether it is still open, and read the last answer. */
@Composable
fun ParentCheckInScreen(view: CheckInViewState, childName: String, onAsk: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    HarborPanel("Check in") {
        when (view.status) {
            CheckInViewStatus.ACCESS_LOST -> HarborNote("You no longer have access to this device's check-ins.", HarborTone.DANGER, emphasis = true)
            CheckInViewStatus.LOAD_FAILED -> HarborNote("Check-ins could not be loaded. Check your connection and try again.", HarborTone.DANGER, emphasis = true)
            CheckInViewStatus.READY -> {
                val open = view.open(now)
                if (open != null) {
                    HarborNote("Waiting for $childName to answer. Asked ${formatTimestamp(open.requestedAt)}.", HarborTone.INFO)
                } else if (onAsk != null) {
                    Text("Ask $childName to check in. Harbor notifies their phone and shows the request in the app. They answer with “I’m okay” or “Running late”.")
                    HarborButton(onAsk, Modifier.fillMaxWidth(), enabled = !view.asking) { Text(if (view.asking) "Asking…" else "Ask $childName to check in") }
                }
                if (view.askFailed) HarborNote("The request did not go through. Try again.", HarborTone.WARN, emphasis = true)
                if (view.offline) Text("Showing the last answers that loaded. They could not be refreshed just now.", style = MaterialTheme.typography.bodySmall)
                val latest = view.latestAnswer
                if (latest != null) LatestAnswer(latest, childName)
                else if (open == null) Text("No check-ins yet.", style = MaterialTheme.typography.bodySmall)
                view.entries.filter { it.status == CheckInStatus.EXPIRED }.take(3).forEach { entry ->
                    HarborRow("Not answered", subtitle = "Asked ${formatTimestamp(entry.requestedAt)}", divider = true)
                }
            }
        }
    }
}

@Composable
private fun LatestAnswer(entry: CheckInEntry, childName: String) {
    val reply = entry.reply ?: return
    HarborRow(if (reply.preset == CheckInPreset.OK) "$childName is okay" else "$childName is running late",
        subtitle = "Answered ${formatTimestamp(reply.repliedAt)}")
    val fix = reply.fix
    if (fix != null) {
        val ageMin = ((locationInstantMs(reply.repliedAt) - locationInstantMs(fix.at)) / 60_000L).coerceAtLeast(0)
        Text("Place sent: ${"%.6f".format(java.util.Locale.US, fix.latitude)}, ${"%.6f".format(java.util.Locale.US, fix.longitude)} " +
            "(about ${fix.accuracyM} m), from ${if (ageMin < 1) "just before the answer" else "$ageMin min before the answer"}.",
            style = MaterialTheme.typography.bodySmall)
    } else {
        Text("No recent place was available with this answer.", style = MaterialTheme.typography.bodySmall)
    }
}
