package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * App chrome shared by every screen (design system: a screen is an `AppBar`, then the page, with a `TabBar` at the
 * bottom). The app column is deep water, at most 420dp wide and centred over open water on wide windows. Content scrolls
 * between the pinned app bar and the floating nav dock, with the 22dp screen gutter; toasts float just above the dock.
 * [centered] places short single-purpose screens (the Parent/Child choice) in the middle. A new [scrollKey] (the selected
 * tab) starts the content at the top instead of carrying the previous page's offset.
 */
@Composable
fun ParentApp(showBrand: Boolean = true, centered: Boolean = false, bottomBar: (@Composable () -> Unit)? = null, scrollKey: Any? = null, content: @Composable () -> Unit) {
    val scroll = rememberSaveable(scrollKey, saver = ScrollState.Saver) { ScrollState(0) }
    val c = harbor
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalContentColor provides c.ink) {
            Column(Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 420.dp).background(c.s).safeDrawingPadding().imePadding()) {
                if (showBrand) HarborAppBar()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(scroll)
                            .padding(start = if (centered) 24.dp else 22.dp, end = if (centered) 24.dp else 22.dp,
                                top = if (showBrand) 18.dp else 24.dp, bottom = 24.dp),
                            verticalArrangement = if (centered) Arrangement.spacedBy(24.dp, Alignment.CenterVertically) else Arrangement.spacedBy(14.dp),
                            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                            content()
                        }
                    }
                    HarborToastHost(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp))
                }
                if (bottomBar != null) bottomBar()
            }
        }
    }
}
