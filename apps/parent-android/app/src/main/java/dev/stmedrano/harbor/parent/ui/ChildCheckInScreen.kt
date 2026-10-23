package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
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

/** The banner on Today while a request is open. Answering is "Share my check-in", never a bare "Check in". */
@Composable
fun CheckInRequestBanner(pending: CheckInPending, onOpen: () -> Unit) {
    HarborNote(HarborTone.WARN) {
        Text("A parent asked you to check in", style = MaterialTheme.typography.titleSmall)
        Text("Asked ${formatClock(pending.requestedAt)}", style = MaterialTheme.typography.bodySmall)
        HarborButton(onOpen, Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Share my check-in") }
    }
}

@Composable
fun CheckInScreen(ui: ChildCheckInUi) {
    val state = ui.state
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val pending = state.pending
        when {
            // Design system `CheckInPrompt`: pick a short reply ("I’m okay" by default), then share. Two taps, no pressure.
            pending != null -> HarborCard {
                var reply by remember(pending.requestId) { mutableStateOf(CheckInPreset.OK) }
                Text("A parent asked you to check in", style = MaterialTheme.typography.titleMedium)
                Text("Asked ${formatClock(pending.requestedAt)}", style = MaterialTheme.typography.bodySmall, color = harbor.ink3)
                Text("Your parents get your answer, the time and your last known place.", style = MaterialTheme.typography.bodyMedium, color = harbor.ink2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplyOption("I’m okay", reply == CheckInPreset.OK, !state.sending, Modifier.weight(1f)) { reply = CheckInPreset.OK }
                    ReplyOption("Running late", reply == CheckInPreset.LATE, !state.sending, Modifier.weight(1f)) { reply = CheckInPreset.LATE }
                }
                HarborButton({ ui.onAnswer(reply) }, Modifier.fillMaxWidth(), enabled = !state.sending) {
                    Text(if (state.sending) "Sending…" else "Share my check-in")
                }
            }
            !state.loaded -> HarborCard {
                HarborEmptyState("Looking for requests…", art = HarborArt.BUOY)
            }
            else -> HarborCard {
                HarborEmptyState("Nothing to answer right now", art = HarborArt.CALM,
                    body = "When a parent asks you to check in, it shows up here and on Today.")
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

/** One preset reply (design system `CheckInPrompt` options): a block that is tinted sea glass when chosen. */
@Composable
private fun ReplyOption(label: String, selected: Boolean, enabled: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val c = harbor
    Box(modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.45f).clip(MaterialTheme.shapes.medium)
        .background(if (selected) c.accs else androidx.compose.ui.graphics.Color.Transparent)
        .border(1.dp, if (selected) c.acc else c.line, MaterialTheme.shapes.medium)
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect),
        contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (selected) c.acc else c.ink2)
    }
}

@Composable
private fun EarlierCheckIns(recent: List<CheckInRecent>) {
    HarborPanel("Earlier") {
        recent.forEachIndexed { index, item ->
            HarborRow(presetWords(item.preset), subtitle = formatTimestamp(item.repliedAt), divider = index > 0,
                leading = { HarborIcon(HarborGlyph.CHECK, harbor.acc) }, trailing = { if (item.located) HarborTag("Place sent", HarborTagTone.LOW) })
        }
    }
}
