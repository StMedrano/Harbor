package dev.stmedrano.harbor.parent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * App chrome shared by every screen (design system: a screen is an `AppBar`, then the page, with a `TabBar` at the
 * bottom). The app column is deep water, at most 420dp wide and centred over open water on wide windows. Content scrolls
 * between the pinned app bar and the floating nav dock, with the 22dp screen gutter; toasts float just above the dock.
 * [centered] places short single-purpose screens (the Parent/Child choice) in the middle. A new [scrollKey] (the selected
 * tab) starts the content at the top instead of carrying the previous page's offset. A new [screenKey] brings the page
 * in (see [rememberHarborArrival]), and the dock slides in and out with the screens that have one.
 */
@Composable
fun ParentApp(showBrand: Boolean = true, centered: Boolean = false, bottomBar: (@Composable () -> Unit)? = null, scrollKey: Any? = null,
    screenKey: Any? = null, screenOrder: Int = 0, content: @Composable () -> Unit) {
    val scroll = rememberSaveable(scrollKey, saver = ScrollState.Saver) { ScrollState(0) }
    val c = harbor
    val arrival = rememberHarborArrival(screenKey, screenOrder, scroll = scroll)
    val reduced = rememberReducedMotion()
    // The dock keeps showing while it slides away, after the screen has stopped offering one.
    val lastBar = remember { arrayOfNulls<@Composable () -> Unit>(1) }
    val bar = bottomBar ?: lastBar[0]
    lastBar[0] = bar
    Box(Modifier.fillMaxSize().background(c.bg), contentAlignment = Alignment.TopCenter) {
        CompositionLocalProvider(LocalContentColor provides c.ink) {
            Column(Modifier.fillMaxHeight().fillMaxWidth().widthIn(max = 420.dp).background(c.s).safeDrawingPadding().imePadding()) {
                if (showBrand) HarborAppBar()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxWidth().heightIn(min = maxHeight).then(arrival).verticalScroll(scroll)
                            .padding(start = if (centered) 24.dp else 22.dp, end = if (centered) 24.dp else 22.dp,
                                top = if (showBrand) 18.dp else 24.dp, bottom = 24.dp),
                            verticalArrangement = if (centered) Arrangement.spacedBy(24.dp, Alignment.CenterVertically) else Arrangement.spacedBy(14.dp),
                            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start) {
                            CompositionLocalProvider(LocalHarborScroll provides scroll) { content() }
                        }
                    }
                    HarborToastHost(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 12.dp))
                }
                AnimatedVisibility(bottomBar != null,
                    enter = if (reduced) EnterTransition.None else expandVertically(tween(HarborDuration.BASE, easing = HarborEasing.settle)) + fadeIn(tween(HarborDuration.BASE)),
                    exit = if (reduced) ExitTransition.None else shrinkVertically(tween(HarborDuration.FAST, easing = HarborEasing.tide)) + fadeOut(tween(HarborDuration.FAST))) {
                    bar?.invoke()
                }
            }
        }
    }
}
