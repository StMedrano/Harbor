package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable fun FamilyEntryScreen(onParent: () -> Unit, onChild: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        HarborBrand()
        Text("Welcome to Harbor Family", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("Who will use this phone?", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        HarborButton(onParent, Modifier.fillMaxWidth()) { Text("Parent") }
        Text("Sign in or create an account to manage your family.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        HarborOutlinedButton(onChild, Modifier.fillMaxWidth()) { Text("Child") }
        Text("Use a pairing code from your parent to connect this phone.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}
