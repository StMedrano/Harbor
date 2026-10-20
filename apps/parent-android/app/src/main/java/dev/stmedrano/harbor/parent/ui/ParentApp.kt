package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * App chrome shared by every screen, like the web `#app` column: content is capped at 480dp and
 * centred on wide windows, scrolls above an optional pinned [bottomBar], and [centered] places short
 * single-purpose screens (the Parent/Child choice) in the middle of the window. A new [scrollKey] (the
 * selected tab) starts the content at the top instead of carrying the previous page's offset. The brand
 * header stays pinned above the scrolling content, with the design system's 18dp screen gutter.
 */
@Composable
fun ParentApp(showBrand: Boolean = true, centered: Boolean = false, bottomBar: (@Composable () -> Unit)? = null, scrollKey: Any? = null, content: @Composable () -> Unit) {
    val scroll = rememberSaveable(scrollKey, saver = ScrollState.Saver) { ScrollState(0) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).fillMaxWidth().widthIn(max = 480.dp)) {
                if (showBrand) Box(Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 4.dp)) { HarborBrand() }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(scroll)
                        .padding(horizontal = if (centered) 24.dp else 18.dp, vertical = if (showBrand) 12.dp else 24.dp),
                        verticalArrangement = if (centered) Arrangement.spacedBy(24.dp, Alignment.CenterVertically) else Arrangement.spacedBy(16.dp),
                        horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                        content()
                    }
                }
            }
            if (bottomBar != null) Box(Modifier.fillMaxWidth().widthIn(max = 480.dp)) { bottomBar() }
        }
    }
}
