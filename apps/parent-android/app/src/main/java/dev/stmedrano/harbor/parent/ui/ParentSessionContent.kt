package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import dev.stmedrano.harbor.parent.auth.ParentIdentity

@Composable
fun ParentSessionContent(identity: ParentIdentity?, authenticationOpen: Boolean,
    authentication: @Composable () -> Unit, parentMenu: @Composable () -> Unit,
    parentContent: @Composable () -> Unit) {
    if (identity == null || authenticationOpen) {
        authentication()
    } else {
        parentMenu()
        parentContent()
    }
}
