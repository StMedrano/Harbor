package dev.stmedrano.harbor.parent.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.sign

/** The design system's `--dur-*`. A page arrives over [ARRIVAL], the same .26s as the web app's `hf-in`. */
object HarborDuration {
    const val FAST = 150
    const val BASE = 220
    const val ARRIVAL = 260
    const val SLOW = 400
    const val METER = 500
}

/** The page's scroll position, so a new page opens at its top rather than at the old page's offset. */
internal val LocalHarborScroll = compositionLocalOf<ScrollState?> { null }

/** Which way a page comes in: from the right going forward through the [order], from the left going back, or rising when level. */
internal fun arrivalDirection(from: Int, to: Int): Int = (to - from).sign

/** Where the sliding tint of a tab row starts, for the tab position [at] (fractional while it moves). */
internal fun pillLeft(at: Float, count: Int, width: Float, gap: Float): Float = at * (pillWidth(count, width, gap) + gap)
internal fun pillWidth(count: Int, width: Float, gap: Float): Float = (width - gap * (count - 1)) / count

private class Arrival(val progress: Animatable<Float, *>, val direction: Int, val changed: Boolean)
private class ArrivalMemory(var key: Any?, var order: Int)

/**
 * A page arriving (design system `settle`): it fades in and moves the last few dp into place when [key] changes. Only the
 * arriving page animates. The old one is gone at once, so no page is ever composed twice, its data is not read twice, and
 * the page's own state is untouched. Applied as a layer, so the page's layout never moves. Nested inside another arrival,
 * pass [playFirst] false so opening the screen fades in once. [toTop] false keeps the scroll position, for content
 * that arrives within a page. With the phone's animations off, the page simply appears.
 */
@Composable
fun rememberHarborArrival(key: Any?, order: Int = 0, playFirst: Boolean = true, toTop: Boolean = true,
    scroll: ScrollState? = LocalHarborScroll.current): Modifier {
    val reduced = rememberReducedMotion()
    val memory = remember { ArrivalMemory(key, order) }
    val arrival = remember(key) {
        val changed = memory.key != key
        val direction = arrivalDirection(memory.order, order)
        memory.key = key
        memory.order = order
        Arrival(Animatable(if (reduced || (!changed && !playFirst)) 1f else 0f), direction, changed)
    }
    LaunchedEffect(key) {
        if (arrival.changed && toTop) scroll?.scrollTo(0)
        arrival.progress.animateTo(1f, tween(HarborDuration.ARRIVAL, easing = HarborEasing.settle))
    }
    return Modifier.graphicsLayer {
        val shown = arrival.progress.value
        alpha = shown
        translationX = arrival.direction * (1f - shown) * 24.dp.toPx()
        translationY = if (arrival.direction == 0) (1f - shown) * 8.dp.toPx() else 0f
    }
}

/** A colour that eases to its new value (`tide`) instead of snapping. */
@Composable
fun harborColor(target: Color): Color {
    val spec: AnimationSpec<Color> = if (rememberReducedMotion()) snap() else tween(HarborDuration.BASE, easing = HarborEasing.tide)
    return animateColorAsState(target, spec, label = "colour").value
}

/** A float that eases to its new value (`tide`) instead of snapping. */
@Composable
fun harborFloat(target: Float): Float {
    val spec: AnimationSpec<Float> = if (rememberReducedMotion()) snap() else tween(HarborDuration.BASE, easing = HarborEasing.tide)
    return animateFloatAsState(target, spec, label = "float").value
}

/** A container that grows and shrinks smoothly when what it holds changes, so a screen doesn't jump as data arrives. */
@Composable
fun harborResize(): Modifier =
    if (rememberReducedMotion()) Modifier else Modifier.animateContentSize(tween(HarborDuration.BASE, easing = HarborEasing.settle))

/**
 * The selected tint of a row of equal tabs, drawn once behind the row so it slides from tab to tab. Goes on the row inside
 * any padding, with the same [gap] as the row's spacing.
 */
@Composable
fun rememberHarborPill(index: Int, count: Int, gap: Dp, corner: Dp, color: Color): Modifier {
    val spec: AnimationSpec<Float> = if (rememberReducedMotion()) snap() else tween(HarborDuration.SLOW, easing = HarborEasing.settle)
    val at by animateFloatAsState(index.coerceAtLeast(0).toFloat(), spec, label = "pill")
    return Modifier.drawBehind {
        if (index < 0 || count <= 0) return@drawBehind
        val width = pillWidth(count, size.width, gap.toPx())
        val left = pillLeft(at, count, size.width, gap.toPx())
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - left - width else left
        drawRoundRect(color, Offset(x, 0f), Size(width, size.height), CornerRadius(corner.toPx()))
    }
}
