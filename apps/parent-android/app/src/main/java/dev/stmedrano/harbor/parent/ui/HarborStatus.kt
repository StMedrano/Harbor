package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One status line in the design system's `SharingStatus` style: an icon chip, a few words ("Sharing location", "Message
 * access enabled") and an optional detail. Sea glass when it's on, amber when something needs fixing (never coral: a paused
 * share is not an emergency). The words carry the meaning, so nothing depends on colour.
 */
@Composable
fun HarborStatus(text: String, ok: Boolean, detail: String? = null, modifier: Modifier = Modifier, glyph: HarborGlyph? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HarborIconChip(glyph ?: if (ok) HarborGlyph.CHECK else HarborGlyph.TOAST_REVIEW, review = !ok)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.titleSmall, color = harbor.ink)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
        }
    }
}
