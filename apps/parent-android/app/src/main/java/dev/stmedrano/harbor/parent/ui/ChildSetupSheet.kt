package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*

@Composable
fun ChildSetupSheet(initialName: String = "", busy: Boolean, error: String?, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(onDismissRequest = { if (!busy) onCancel() }, title = { Text("Add child", style = MaterialTheme.typography.titleMedium) },
        text = {
            androidx.compose.foundation.layout.Column {
                HarborField(name, { name = it }, "Child name", enabled = !busy, hint = "Shown only to your family.")
                if (error != null) HarborNote(error, HarborTone.DANGER, Modifier.padding(top = 8.dp), emphasis = true)
                if (busy) CircularProgressIndicator()
            }
        }, confirmButton = { TextButton({ onSubmit(name) }, enabled = !busy && name.isNotBlank()) { Text("Save child / retry") } },
        dismissButton = { TextButton(onCancel, enabled = !busy) { Text("Cancel request") } })
}
