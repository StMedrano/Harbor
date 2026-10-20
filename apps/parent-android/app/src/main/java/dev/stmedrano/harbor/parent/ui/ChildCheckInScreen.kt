package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.checkin.CheckInPreset
import dev.stmedrano.harbor.parent.checkin.CheckInPending
import dev.stmedrano.harbor.parent.checkin.CheckInProblem
import dev.stmedrano.harbor.parent.checkin.CheckInRecent
import dev.stmedrano.harbor.parent.checkin.CheckInUiState
import dev.stmedrano.harbor.parent.profile.ProfileLease
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the child's check-in screens need. Answering is only possible while a parent's request is open. */
data class ChildCheckInUi(val state: CheckInUiState, val onAnswer: (CheckInPreset) -> Unit, val onRetry: () -> Unit)

/** Re-reads the requests when the app comes back to the front and then every minute while this screen is composed. */
@Composable
fun rememberChildCheckInUi(graph: ParentApplication, lease: ProfileLease, resumeTick: Int): ChildCheckInUi? {
    var model by remember(lease) { mutableStateOf(graph.childCheckInModel) }
    LaunchedEffect(lease, resumeTick) {
        while (true) {
            val current = graph.childCheckInModel
            model = current
            current?.refresh(lease)
            delay(if (current == null) 1_000L else 60_000L)
        }
    }
    val scope = rememberCoroutineScope()
    val active = model ?: return null
    val state = active.state.collectAsState().value
    return ChildCheckInUi(state, { preset -> scope.launch { active.reply(lease, preset) } }, { scope.launch { active.refresh(lease) } })
}

internal fun presetWords(preset: CheckInPreset) = if (preset == CheckInPreset.OK) "Checked in, I’m okay" else "Checked in, running late"

/** The banner on Today while a request is open. */
@Composable
fun CheckInRequestBanner(pending: CheckInPending, onOpen: () -> Unit) {
    HarborNote(HarborTone.WARN) {
        Text("A parent asked you to check in", style = MaterialTheme.typography.titleSmall)
        Text("Asked ${formatTimestamp(pending.requestedAt)}")
        HarborButton(onOpen, Modifier.fillMaxWidth()) { Text("Check in") }
    }
}

@Composable
fun CheckInScreen(ui: ChildCheckInUi) {
    val state = ui.state
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val pending = state.pending
        when {
            pending != null -> HarborCard {
                Text("A parent asked you to check in", style = MaterialTheme.typography.titleMedium)
                Text("Asked ${formatTimestamp(pending.requestedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Your parents get your answer, the time and your last known place.", style = MaterialTheme.typography.bodyMedium)
                HarborButton({ ui.onAnswer(CheckInPreset.OK) }, Modifier.fillMaxWidth(), enabled = !state.sending) { Text("I’m okay") }
                HarborOutlinedButton({ ui.onAnswer(CheckInPreset.LATE) }, Modifier.fillMaxWidth(), enabled = !state.sending) { Text("Running late") }
                if (state.sending) Text("Sending…", style = MaterialTheme.typography.bodySmall)
            }
            !state.loaded -> HarborCard {
                Text("Looking for requests…", style = MaterialTheme.typography.titleMedium)
            }
            else -> HarborCard {
                Text("Nothing to answer right now", style = MaterialTheme.typography.titleMedium)
            }
        }
        when (state.problem) {
            CheckInProblem.COULD_NOT_LOAD -> {
                HarborNote("Harbor could not look for requests just now.", HarborTone.WARN, emphasis = true)
                HarborOutlinedButton(ui.onRetry, Modifier.fillMaxWidth()) { Text("Try again") }
            }
            CheckInProblem.COULD_NOT_SEND -> HarborNote("Your answer was not sent. Check your connection and try again.", HarborTone.WARN, emphasis = true)
            CheckInProblem.REQUEST_ENDED -> HarborNote("That request has already ended, so nothing was sent.", HarborTone.WARN, emphasis = true)
            null -> Unit
        }
        if (state.recent.isNotEmpty()) EarlierCheckIns(state.recent)
    }
}

@Composable
private fun EarlierCheckIns(recent: List<CheckInRecent>) {
    HarborPanel("Earlier") {
        recent.forEachIndexed { index, item ->
            HarborRow(presetWords(item.preset), subtitle = formatTimestamp(item.repliedAt), divider = index > 0,
                trailing = { if (item.located) HarborTag("Place sent") })
        }
    }
}
