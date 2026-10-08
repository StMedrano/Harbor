package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp

@Composable fun FamilyEntryScreen(onParent: () -> Unit, onChild: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Welcome to Harbor Family", style = MaterialTheme.typography.headlineMedium)
        Text("Who will use this phone?")
        Button(onClick = onParent) { Text("Parent") }
        Text("Sign in or create an account to manage your family.")
        OutlinedButton(onClick = onChild) { Text("Child") }
        Text("Use a pairing code from your parent to connect this phone.")
    }
}
