package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.PairingState

/**
 * Design system `PairingCode`: three numbered steps, the six digits grouped 3 + 3, and how long the code works. An expired
 * code is struck through so nobody types it, with "Get a new code". The parent never types a code into their own phone.
 */
@Composable
fun PairingSheet(state: PairingState, expired: Boolean, error: String?, onRenew: () -> Unit, onCheck: () -> Unit, onClose: () -> Unit) {
    val c = harbor
    AlertDialog(onDismissRequest = onClose, containerColor = c.s2, title = { Text("Pair child device", style = MaterialTheme.typography.titleLarge) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) CircularProgressIndicator(color = c.acc)
            val code = state.code
            if (state.enrolled) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    HarborIconChip(HarborGlyph.CHECK)
                    Text("Enrollment confirmed by a fresh device read.", style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PairingStep(1, "On your child's phone, install Harbor Family.")
                    PairingStep(2, "Open it and choose Child.")
                    PairingStep(3, "Enter this code.")
                }
            }
            if (code != null) {
                Surface(shape = MaterialTheme.shapes.medium, color = c.s, border = BorderStroke(1.dp, c.line), modifier = Modifier.fillMaxWidth()) {
                    Text(code.code.chunked(3).joinToString(" "), Modifier.padding(horizontal = 8.dp, vertical = 14.dp), style = HarborCodeStyle,
                        color = if (expired) c.ink3 else c.ink, textAlign = TextAlign.Center,
                        textDecoration = if (expired) TextDecoration.LineThrough else null)
                }
                if (expired) Text("Code expired. Get a new code to pair.", style = MaterialTheme.typography.bodyMedium, color = c.warni,
                    fontWeight = FontWeight.SemiBold)
                else if (!state.enrolled) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.acc))
                    Text("Works until ${formatClock(code.expiresAt)}. Waiting for the phone.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
                }
            }
            if (!state.enrolled && code != null && !expired) Text("Then check enrollment here.", style = MaterialTheme.typography.bodySmall, color = c.ink2)
            if (error != null) HarborNote(error, HarborTone.DANGER, emphasis = true)
            if (code == null || expired) HarborButton(onRenew, Modifier.fillMaxWidth(), enabled = !state.loading) { Text("Get a new code") }
            HarborSecondaryButton(onCheck, Modifier.fillMaxWidth(), enabled = !state.loading) { Text("Check enrollment") }
        }
    }, confirmButton = { TextButton(onClose) { Text("Close") } })
}

@Composable
private fun PairingStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(harbor.accs), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = harbor.acc)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = harbor.ink2)
    }
}
