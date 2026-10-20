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
                Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Text(if (expired) "Code expired" else code.code, Modifier.padding(16.dp), style = if (expired) MaterialTheme.typography.titleMedium else HarborCodeStyle,
                        color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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
