package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.PairingState

@Composable
fun PairingSheet(state: PairingState, expired: Boolean, error: String?, onRenew: () -> Unit, onCheck: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Pair child device", style = MaterialTheme.typography.titleMedium) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) CircularProgressIndicator()
            state.code?.let { code ->
                // Design system `PairingCode`: the six digits grouped 3 + 3 on deep water; an expired code says so in amber.
                Surface(shape = MaterialTheme.shapes.medium, color = harbor.s, border = androidx.compose.foundation.BorderStroke(1.dp, harbor.line),
                    modifier = Modifier.fillMaxWidth()) {
                    Text(if (expired) "Code expired" else code.code.chunked(3).joinToString(" "), Modifier.padding(horizontal = 8.dp, vertical = 14.dp),
                        style = if (expired) MaterialTheme.typography.titleMedium else HarborCodeStyle,
                        color = if (expired) harbor.warni else harbor.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
                Text("Expires ${code.expiresAt}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (state.enrolled) "Enrollment confirmed by a fresh device read." else "Enter this code on the child device, then check enrollment.")
            if (error != null) HarborNote(error, HarborTone.DANGER, emphasis = true)
            HarborOutlinedButton(onRenew, enabled = !state.loading) { Text("Renew pairing code") }
            HarborOutlinedButton(onCheck, enabled = !state.loading) { Text("Check enrollment") }
        }
    }, confirmButton = { TextButton(onClose) { Text("Close") } })
}
