package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text

@Composable fun ParentApprovalScreen(onSignIn: suspend (String, String) -> Boolean,
    onVerify: suspend (String) -> Boolean, onRemove: suspend () -> Boolean, onCancel: () -> Unit) {
    Text("Parent approval required")
}
