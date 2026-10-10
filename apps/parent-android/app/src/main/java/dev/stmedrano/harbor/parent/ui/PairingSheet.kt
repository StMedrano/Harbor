package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.PairingState

@Composable
fun PairingSheet(state: PairingState, expired: Boolean, error: String?, onRenew: () -> Unit, onCheck: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Pair child device") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.loading) CircularProgressIndicator()
            state.code?.let { code ->
                Text(if (expired) "Code expired" else code.code, style = MaterialTheme.typography.headlineLarge)
                Text("Expires ${code.expiresAt}")
            }
            Text(if (state.enrolled) "Enrollment confirmed by a fresh device read." else "Enter this code on the child device, then check enrollment.")
            if (error != null) Text(error)
            HarborOutlinedButton(onRenew, enabled = !state.loading) { Text("Renew pairing code") }
            HarborOutlinedButton(onCheck, enabled = !state.loading) { Text("Check enrollment") }
        }
    }, confirmButton = { TextButton(onClose) { Text("Close") } })
}
