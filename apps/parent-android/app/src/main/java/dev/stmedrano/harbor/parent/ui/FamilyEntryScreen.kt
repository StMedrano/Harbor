package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable fun FamilyEntryScreen(onParent: () -> Unit, onChild: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Welcome to Harbor Family", style = MaterialTheme.typography.headlineMedium)
        Text("Who will use this phone?", color = MaterialTheme.colorScheme.onSurfaceVariant)
        HarborButton(onParent, Modifier.fillMaxWidth()) { Text("Parent") }
        Text("Sign in or create an account to manage your family.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        HarborOutlinedButton(onChild, Modifier.fillMaxWidth()) { Text("Child") }
        Text("Use a pairing code from your parent to connect this phone.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
