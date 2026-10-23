package dev.stmedrano.harbor.parent.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Harbor's motion curves (design system `easing`). */
object HarborEasing {
    val settle = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val tide = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val swell = CubicBezierEasing(0f, 0f, 0.2f, 1f)
}

/** True when the phone's animations are turned off (Accessibility > Remove animations): Harbor's motion stops, the words stay. */
@Composable
fun rememberReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * Design system `Field`: an uppercase label above a 48dp input on the harbor surface; focus turns the border brand.
 * Guidance goes in [hint]; an [error] replaces the hint and is announced with the field. The label sits inside the field
 * so TalkBack (and tests) read it as the field's name.
 */
@Composable
fun HarborField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    hint: String? = null, error: String? = null, singleLine: Boolean = true, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None, textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    trailing: (@Composable () -> Unit)? = null) {
    val c = harbor
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val border = when { error != null -> c.dan; focused -> c.brand; else -> c.line }
    BasicTextField(value, onValueChange, modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f)
        .semantics { if (error != null) this.error(error) },
        enabled = enabled, singleLine = singleLine, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        textStyle = textStyle.copy(color = c.ink), cursorBrush = SolidColor(c.brand), interactionSource = interaction,
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // Shown in capitals; read as written.
                Box(Modifier.clearAndSetSemantics { text = AnnotatedString(label) }) {
                    Text(label.uppercase(), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = c.ink2)
                }
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium).background(c.s2)
                    .border(1.dp, border, MaterialTheme.shapes.medium).padding(start = 14.dp, end = if (trailing != null) 4.dp else 14.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).padding(vertical = 12.dp)) { inner() }
                    trailing?.invoke()
                }
                when {
                    error != null -> Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold, color = c.dan)
                    hint != null -> Text(hint, style = MaterialTheme.typography.bodySmall, color = c.ink3)
                }
            }
        })
}

/**
 * Design system `Sheet`: deep water with a grab handle and 22dp top corners over the scrim; a title, the content, then
 * paired block buttons with the primary on the right.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HarborSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = harbor
    ModalBottomSheet(onDismiss, containerColor = c.s, contentColor = c.ink, scrimColor = c.scrim,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 14.dp).size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(c.line)) }) {
        Column(Modifier.padding(horizontal = 22.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

/** Design system `Stat`: one figure in a small tile, a short label under it. A dash, not 0, until the phone reports. */
@Composable
fun HarborStat(value: String?, label: String, modifier: Modifier = Modifier) {
    val c = harbor
    Column(modifier.clip(MaterialTheme.shapes.large).background(c.s2).border(1.dp, c.line, MaterialTheme.shapes.large)
        .padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(value ?: "—", style = HarborMetricStyle.copy(fontSize = 22.sp, lineHeight = 28.sp), color = c.ink)
        Text(label, style = MaterialTheme.typography.bodySmall, color = c.ink2)
    }
}

/** Two stats per row (design system `StatGrid`). */
@Composable
fun HarborStatRow(vararg stats: Pair<String?, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.forEach { (value, label) -> HarborStat(value, label, Modifier.weight(1f)) }
    }
}

/** Design system `UsageMeter` figure: "1h 20m / 2h", the limit after a slash in a smaller, quieter size. */
@Composable
fun HarborUsageFigure(used: String, limit: String? = null) {
    val c = harbor
    Row(verticalAlignment = Alignment.Bottom) {
        Text(used, style = HarborMetricStyle, color = c.ink)
        if (limit != null) Text(" / $limit", Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium, color = c.ink2)
    }
}

/** One entry of a day (design system `Timeline`): sea-glass dots for app time and places, amber for rules that stepped in. */
data class HarborTimelineItem(val time: String, val text: String, val review: Boolean = false)

@Composable
fun HarborTimeline(items: List<HarborTimelineItem>) {
    val c = harbor
    Column {
        items.forEach { item ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Text(item.time, Modifier.width(70.dp).padding(top = 2.dp), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
                    color = c.ink3)
                Box(Modifier.padding(top = 7.dp, end = 10.dp).size(16.dp).clip(CircleShape).background(if (item.review) c.warns else c.accs),
                    contentAlignment = Alignment.Center) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(if (item.review) c.warn else c.acc))
                }
                Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp), color = c.ink)
            }
        }
    }
}

/** A step of a lifecycle (design system `CheckInRequest`): done (filled), now (ringed), waiting too long (amber), or still to come. */
enum class HarborStepState { DONE, NOW, LATE, PENDING }

/**
 * One step with its dot, title, detail and time. A [sonar] ring repeats on the dot only while someone is waiting on a
 * person (the check-in sonar), and never when animations are off; the words say the same thing.
 */
@Composable
fun HarborStep(state: HarborStepState, title: String, detail: String? = null, time: String? = null, first: Boolean = false, last: Boolean = false,
    sonar: Boolean = false) {
    val c = harbor
    val reduced = rememberReducedMotion()
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // One continuous line joins the dots: from this dot's centre to the row's edge, and from the edge to the dot.
        Box(Modifier.width(20.dp).fillMaxHeight().drawBehind {
            val centre = 18.dp.toPx()
            val top = if (first) centre else 0f
            val bottom = if (last) centre else size.height
            if (bottom > top) drawLine(c.line, Offset(size.width / 2, top), Offset(size.width / 2, bottom), strokeWidth = 2.dp.toPx())
        }) {
            Box(Modifier.padding(top = 8.dp).size(20.dp), contentAlignment = Alignment.Center) {
                if (sonar && !reduced) {
                    val pulse = rememberInfiniteTransition(label = "sonar")
                    val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = HarborEasing.swell), RepeatMode.Restart), label = "ring")
                    Box(Modifier.matchParentSize().drawBehind {
                        val grow = 1f + 1.4f * t
                        drawCircle(c.acc.copy(alpha = 0.8f * (1f - (t / 0.8f).coerceAtMost(1f))), radius = size.minDimension / 2 * grow, style = Stroke(2.dp.toPx()))
                    })
                }
                val (fill, ring) = when (state) {
                    HarborStepState.DONE -> c.acc to c.acc
                    HarborStepState.NOW -> c.s2 to c.acc
                    HarborStepState.LATE -> c.warns to c.warn
                    HarborStepState.PENDING -> c.s2 to c.line
                }
                Box(Modifier.size(20.dp).clip(CircleShape).background(fill).border(2.dp, ring, CircleShape))
            }
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = if (state == HarborStepState.PENDING) FontWeight.Medium else FontWeight.SemiBold,
                color = if (state == HarborStepState.PENDING) c.ink3 else c.ink)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (state == HarborStepState.LATE) c.warni else c.ink2)
        }
        if (time != null) Text(time, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = c.ink3)
    }
}

/** A back link at the top of a sub-page: a chevron and "Back". */
@Composable
fun HarborBackButton(onBack: () -> Unit, label: String = "Back") {
    TextButton(onBack, contentPadding = PaddingValues(start = 0.dp, end = 12.dp)) {
        // The right chevron, mirrored.
        HarborIcon(HarborGlyph.CHEVRON, harbor.acc, Modifier.size(20.dp).graphicsLayer { scaleX = -1f })
        Spacer(Modifier.width(4.dp))
        Text(label, color = harbor.acc)
    }
}

/** A small round icon chip in a tone: sea glass for what's fine, amber for something to fix. */
@Composable
fun HarborIconChip(glyph: HarborGlyph, review: Boolean = false, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val c = harbor
    Box(Modifier.size(size).clip(CircleShape).background(if (review) c.warns else c.accs), contentAlignment = Alignment.Center) {
        HarborIcon(glyph, if (review) c.warni else c.acc, Modifier.size(size * 0.55f), strokeWidth = 2.1f)
    }
}
