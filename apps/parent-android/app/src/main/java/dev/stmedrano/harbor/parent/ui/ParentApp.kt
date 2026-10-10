package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * App chrome shared by every screen, like the web `#app` column: content is capped at 480dp and
 * centred on wide windows, scrolls above an optional pinned [bottomBar], and [centered] places short
 * single-purpose screens (the Parent/Child choice) in the middle of the window.
 */
@Composable
fun ParentApp(showBrand: Boolean = true, centered: Boolean = false, bottomBar: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().widthIn(max = 480.dp)) {
                Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = if (centered) Arrangement.spacedBy(24.dp, Alignment.CenterVertically) else Arrangement.spacedBy(24.dp),
                    horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                    if (showBrand) HarborBrand()
                    content()
                }
            }
            if (bottomBar != null) Box(Modifier.fillMaxWidth().widthIn(max = 480.dp)) { bottomBar() }
        }
    }
}
