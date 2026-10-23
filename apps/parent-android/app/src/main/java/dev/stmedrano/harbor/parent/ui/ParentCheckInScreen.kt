package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Column
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
import dev.stmedrano.harbor.parent.location.locationAgeLabel
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

/** After this long without an answer, an open request reads "No response yet" in amber (design system `CheckInRequest`). */
const val CHECK_IN_NO_RESPONSE_MS = 10 * 60_000L

/** Which step of a request's life to show: still waiting, or waiting long enough to say there is no response yet. */
fun checkInWaitingTooLong(entry: CheckInEntry, nowMs: Long): Boolean = nowMs - locationInstantMs(entry.requestedAt) >= CHECK_IN_NO_RESPONSE_MS

/**
 * A parent's check-ins for one phone (design system `CheckInRequest`): "Request Leo's check-in", then the request's life
 * (Request sent, Waiting for Leo, then Leo checked in or No response yet), and the last answer. Asking someone else is
 * never a bare "Check in".
 */
@Composable
fun ParentCheckInScreen(view: CheckInViewState, childName: String, onAsk: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    HarborPanel("Check-ins") {
        when (view.status) {
            CheckInViewStatus.ACCESS_LOST -> HarborNote("You no longer have access to this device's check-ins.", HarborTone.DANGER, emphasis = true)
            CheckInViewStatus.LOAD_FAILED -> HarborNote("Check-ins could not be loaded. Check your connection and try again.", HarborTone.DANGER, emphasis = true)
            CheckInViewStatus.READY -> {
                val open = view.open(now)
                if (open != null) OpenRequest(open, childName, now)
                else {
                    val latest = view.latestAnswer
                    if (latest != null) LatestAnswer(latest, childName)
                    else Text("No check-ins yet. When you ask, $childName answers with a short note and this phone's place.",
                        style = MaterialTheme.typography.bodyMedium, color = harbor.ink2)
                    if (onAsk != null) HarborButton(onAsk, Modifier.fillMaxWidth(), enabled = !view.asking, loading = view.asking) {
                        Text("Request $childName's check-in")
                    }
                }
                if (view.askFailed) HarborNote("The request did not go through. Try again.", HarborTone.WARN, emphasis = true)
                if (view.offline) Text("Showing the last answers that loaded. They could not be refreshed just now.", style = MaterialTheme.typography.bodySmall,
                    color = harbor.ink2)
                view.entries.filter { it.status == CheckInStatus.EXPIRED }.take(3).forEach { entry ->
                    HarborRow("Not answered", subtitle = "Asked ${formatTimestamp(entry.requestedAt)}", divider = true,
                        leading = { HarborIcon(HarborGlyph.HOURGLASS, harbor.ink3) })
                }
            }
        }
    }
}

@Composable
private fun OpenRequest(open: CheckInEntry, childName: String, now: Long) {
    val asked = locationInstantMs(open.requestedAt)
    Column {
        HarborStep(HarborStepState.DONE, "Request sent", time = formatClock(open.requestedAt), first = true)
        if (checkInWaitingTooLong(open, now)) HarborStep(HarborStepState.LATE, "No response yet",
            detail = "Asked ${locationAgeLabel(now - asked)}. The request stays open until ${formatTimestamp(open.expiresAt)}.")
        else HarborStep(HarborStepState.NOW, "Waiting for $childName", detail = "$childName gets a notification and can answer in two taps.", sonar = true)
        HarborStep(HarborStepState.PENDING, "$childName checked in", last = true)
    }
}

@Composable
private fun LatestAnswer(entry: CheckInEntry, childName: String) {
    val reply = entry.reply ?: return
    HarborRow(if (reply.preset == CheckInPreset.OK) "$childName is okay" else "$childName is running late",
        subtitle = "Checked in ${formatTimestamp(reply.repliedAt)}", leading = { HarborIconChip(HarborGlyph.CHECK) })
    val fix = reply.fix
    if (fix != null) {
        val ageMin = ((locationInstantMs(reply.repliedAt) - locationInstantMs(fix.at)) / 60_000L).coerceAtLeast(0)
        Text("Place sent: ${"%.6f".format(java.util.Locale.US, fix.latitude)}, ${"%.6f".format(java.util.Locale.US, fix.longitude)} " +
            "(about ${fix.accuracyM} m), from ${if (ageMin < 1) "just before the answer" else "$ageMin min before the answer"}.",
            style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
    } else {
        Text("No recent place was available with this answer.", style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
    }
}
