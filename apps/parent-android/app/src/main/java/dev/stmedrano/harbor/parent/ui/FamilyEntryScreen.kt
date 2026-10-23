package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable fun FamilyEntryScreen(onParent: () -> Unit, onChild: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        // The full logo where the name is not already on screen (design system `Logo` lockup, at least 140dp wide).
        HarborLogo(HarborLogoVariant.LOCKUP, 168.dp, Modifier.padding(bottom = 8.dp))
        HarborEyebrow("A calmer kind of safety")
        Text("Welcome", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text("Who will use this phone?", color = harbor.ink2, textAlign = TextAlign.Center)
        HarborButton(onParent, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Parent") }
        Text("Sign in or create an account to manage your family.", style = MaterialTheme.typography.bodySmall,
            color = harbor.ink2, textAlign = TextAlign.Center)
        HarborSecondaryButton(onChild, Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Child") }
        Text("Use a pairing code from your parent to connect this phone.", style = MaterialTheme.typography.bodySmall,
            color = harbor.ink2, textAlign = TextAlign.Center)
    }
}
