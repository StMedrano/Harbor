package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.child.ChildFailure
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.profile.ProfileState
import dev.stmedrano.harbor.parent.profile.ProfileBlock

@Composable
fun FamilyRoleContent(state: FamilyEntryState, entry: @Composable () -> Unit,
    parentAuth: @Composable () -> Unit, parentMenu: @Composable () -> Unit,
    parentContent: @Composable () -> Unit, childPairing: @Composable () -> Unit,
    childContent: @Composable () -> Unit, onRetry: (() -> Unit)? = null, detail: String? = null) {
    when (state.destination()) {
        FamilyDestination.ENTRY -> entry()
        FamilyDestination.PARENT_AUTH -> parentAuth()
        FamilyDestination.PARENT_HOME -> { parentMenu(); parentContent() }
        FamilyDestination.CHILD_PAIRING -> childPairing()
        FamilyDestination.CHILD_HOME -> childContent()
        FamilyDestination.BLOCKED -> {
            val network = (state.profile as? ProfileState.Blocked)?.reason == ProfileBlock.NETWORK_UNAVAILABLE
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (network) "Connection unavailable. Connect and retry your saved setup." else "This phone needs a parent to check its setup.")
                if ((state.childState as? ChildSyncState.Blocked)?.reason == ChildFailure.REVOKED) {
                    Text("A parent removed this phone from the family, so it no longer shares anything. To use it again, clear Harbor's storage " +
                        "(Android Settings > Apps > Harbor > Storage > Clear storage), open Harbor, and choose Child with a new pairing code.")
                }
                if (!detail.isNullOrBlank()) Text("Technical detail: $detail", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                if (network && onRetry != null) HarborButton(onClick = onRetry) { Text("Retry saved setup") }
            }
        }
        FamilyDestination.RESTORING -> Text("Restoring your family setup…")
    }
}
