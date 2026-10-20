package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.legal.LegalBlock
import dev.stmedrano.harbor.parent.legal.LegalDoc
import dev.stmedrano.harbor.parent.legal.fillLegalPlaceholders
import dev.stmedrano.harbor.parent.legal.parseLegal

/** Reads a document shipped with the app. A missing file shows a plain message instead of crashing. */
@Composable
fun rememberLegalBlocks(doc: LegalDoc): List<LegalBlock> {
    val context = LocalContext.current
    return remember(doc) {
        try { parseLegal(fillLegalPlaceholders(context.assets.open(doc.assetPath).bufferedReader().use { it.readText() })) }
        catch (_: Exception) { listOf(LegalBlock.Title(doc.label), LegalBlock.Paragraph("This page could not be loaded.")) }
    }
}

@Composable
fun LegalBody(blocks: List<LegalBlock>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            when (block) {
                is LegalBlock.Title -> Text(block.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                is LegalBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
                is LegalBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                is LegalBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(block.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** A full-page document with a Back button, used for About, Privacy policy and Terms of service. */
@Composable
fun LegalScreen(doc: LegalDoc, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onBack) { Text("Back") }
        LegalBody(rememberLegalBlocks(doc))
    }
}

/** The two policy buttons shown under About. */
@Composable
fun PolicyLinks(onOpen: (LegalDoc) -> Unit) {
    HarborCard {
        HarborRow("Privacy policy", onClick = { onOpen(LegalDoc.PRIVACY) }, trailing = { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) })
        HarborRow("Terms of service", divider = true, onClick = { onOpen(LegalDoc.TERMS) }, trailing = { Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant) })
    }
}
