package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** [centered] places short, single-purpose screens (the Parent/Child choice) in the middle of the window; taller content still scrolls. */
@Composable
fun ParentApp(showBrand: Boolean = true, centered: Boolean = false, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = if (centered) Arrangement.spacedBy(24.dp, Alignment.CenterVertically) else Arrangement.spacedBy(24.dp),
                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                if (showBrand) HarborBrand()
                content()
            }
        }
    }
}
