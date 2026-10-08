package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text

@Composable
fun FamilyRoleContent(state: FamilyEntryState, entry: @Composable () -> Unit,
    parentAuth: @Composable () -> Unit, parentMenu: @Composable () -> Unit,
    parentContent: @Composable () -> Unit, childPairing: @Composable () -> Unit,
    childContent: @Composable () -> Unit, onRetry: (() -> Unit)? = null) {
    when (state.destination()) {
        FamilyDestination.ENTRY -> entry()
        FamilyDestination.PARENT_AUTH -> parentAuth()
        FamilyDestination.PARENT_HOME -> { parentMenu(); parentContent() }
        FamilyDestination.CHILD_PAIRING -> childPairing()
        FamilyDestination.CHILD_HOME -> childContent()
        FamilyDestination.BLOCKED -> Text("This phone needs a parent to check its setup.")
        FamilyDestination.RESTORING -> Text("Restoring your family setup…")
    }
}

