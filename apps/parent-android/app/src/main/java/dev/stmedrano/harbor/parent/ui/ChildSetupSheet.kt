package dev.stmedrano.harbor.parent.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
fun ChildSetupSheet(initialName: String = "", busy: Boolean, error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("Add child") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(name, { name = it }, label = { Text("Child name") }, enabled = !busy, singleLine = true)
                if (error != null) Text(error)
                if (busy) CircularProgressIndicator()
            }
        }, confirmButton = { TextButton({ onSubmit(name) }, enabled = !busy && name.isNotBlank()) { Text("Save child / retry") } },
        dismissButton = { TextButton(onCancel, enabled = !busy) { Text("Cancel request") } })
}
