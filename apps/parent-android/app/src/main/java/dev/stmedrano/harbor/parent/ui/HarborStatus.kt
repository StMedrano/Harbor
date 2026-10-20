package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * One short status line: a dot and a few words ("Sharing location", "Message access enabled"). The dot is decoration;
 * the words carry the meaning, so nothing depends on colour. Everything longer lives in About and the policies.
 */
@Composable
fun HarborStatus(text: String, ok: Boolean, detail: String? = null, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (ok) scheme.secondary else scheme.tertiary))
        Column {
            Text(text, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
