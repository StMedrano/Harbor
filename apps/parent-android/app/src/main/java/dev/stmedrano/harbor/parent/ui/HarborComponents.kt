package dev.stmedrano.harbor.parent.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** The Harbor mark (a home outline with a Beacon light) beside the "Harbor Family" wordmark in the Harbor colour. */
@Composable
fun HarborBrand() {
    val brand = MaterialTheme.colorScheme.primary
    val onBrand = MaterialTheme.colorScheme.onPrimary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Canvas(Modifier.size(30.dp)) {
            val unit = size.width / 32f
            drawRoundRect(brand, cornerRadius = CornerRadius(8f * unit))
            val house = Path().apply {
                moveTo(8f * unit, 15f * unit); lineTo(16f * unit, 8f * unit)
                lineTo(24f * unit, 15f * unit); lineTo(24f * unit, 24f * unit)
                lineTo(8f * unit, 24f * unit); close()
            }
            drawPath(house, onBrand, style = Stroke(2.4f * unit, join = StrokeJoin.Round))
            drawCircle(Color(0xFFE9A23B), 2.6f * unit, Offset(16f * unit, 18f * unit))
        }
        Text("Harbor Family", style = MaterialTheme.typography.titleLarge, color = brand)
    }
}

data class HarborTab(val label: String, val selected: Boolean, val onClick: () -> Unit)

/** Segmented control (design system `Segmented`): tinted track, raised selected pill. */
@Composable
fun HarborTabs(tabs: List<HarborTab>, modifier: Modifier = Modifier) {
    val track = RoundedCornerShape(10.dp)
    val pill = RoundedCornerShape(8.dp)
    Row(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, track).padding(3.dp)) {
        tabs.forEach { tab ->
            Box(Modifier.weight(1f).heightIn(min = 44.dp)
                .then(if (tab.selected) Modifier.shadow(1.dp, pill) else Modifier)
                .clip(pill)
                .background(if (tab.selected) MaterialTheme.colorScheme.surface else Color.Transparent)
                .selectable(selected = tab.selected, role = Role.Tab, onClick = tab.onClick)
                .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(tab.label, style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (tab.selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (tab.selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Round initial. Give it a family colour (see [HarborKid]); white initials, as in the design system. */
@Composable
fun HarborAvatar(name: String, size: Dp = 30.dp, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(shape = CircleShape, color = color, contentColor = Color.White, modifier = Modifier.size(size)) {
        Box(contentAlignment = Alignment.Center) {
            Text(name.take(1).uppercase(), style = MaterialTheme.typography.labelMedium, fontSize = if (size >= 40.dp) 17.sp else 13.sp)
        }
    }
}

/** Primary action: Harbor fill, 12dp corners, at least 48dp tall. */
@Composable
fun HarborButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f)),
        content = content)

/** Secondary action: the raised-surface fill with ink text (design system `Button` secondary). */
@Composable
fun HarborSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
        content = content)

/** Outline action: 1.5dp line border, ink text. */
@Composable
fun HarborOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)),
        content = content)

/** Destructive action (for example revoking a device): Flare outline and text, never filled, so it is hard to hit by accident. */
@Composable
fun HarborDestructiveButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    OutlinedButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = if (enabled) 1f else 0.5f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error,
            disabledContentColor = MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        content = content)

/** Web `.panel`: raised surface, 14dp corners, no border. */
@Composable
fun HarborCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHighest, modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** A section label (13sp semibold, secondary ink) above a [HarborCard]. */
@Composable
fun HarborPanel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HarborCard(content = content)
    }
}

enum class HarborTone { INFO, WARN, DANGER }

/**
 * Notes and banners. INFO is the calm Kelp callout, WARN is Beacon (caution, such as stale data) and DANGER is Flare
 * (errors, loss of access). The tone is always carried by the words too, never by colour alone.
 */
@Composable
fun HarborNote(tone: HarborTone = HarborTone.INFO, modifier: Modifier = Modifier, emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val (container, ink) = when (tone) {
        HarborTone.INFO -> scheme.primaryContainer to scheme.onSurfaceVariant
        HarborTone.WARN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        HarborTone.DANGER -> scheme.errorContainer to scheme.onErrorContainer
    }
    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = ink, modifier = modifier.fillMaxWidth()) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
            fontSize = 13.sp, fontWeight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
fun HarborNote(text: String, tone: HarborTone = HarborTone.INFO, modifier: Modifier = Modifier, emphasis: Boolean = false) =
    HarborNote(tone, modifier, emphasis) { Text(text) }

enum class HarborTagTone { NEUTRAL, HIGH, MEDIUM, LOW }

/** Small pill label such as "Active". */
@Composable
fun HarborTag(text: String, tone: HarborTagTone = HarborTagTone.NEUTRAL) {
    val scheme = MaterialTheme.colorScheme
    val (container, ink) = when (tone) {
        HarborTagTone.NEUTRAL -> scheme.surface to scheme.onSurfaceVariant
        HarborTagTone.HIGH -> scheme.errorContainer to scheme.error
        HarborTagTone.MEDIUM -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        HarborTagTone.LOW -> scheme.primaryContainer to scheme.secondary
    }
    Surface(shape = CircleShape, color = container, contentColor = ink) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * One row of a panel: title over a secondary line, optional leading and trailing content, at least 44dp tall.
 * Rows after the first draw a hairline above themselves, as in the design system.
 */
@Composable
fun HarborRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, divider: Boolean = false,
    onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().heightIn(min = 44.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

data class HarborNavItem(val label: String, val glyph: HarborGlyph, val selected: Boolean, val onClick: () -> Unit)

/** Bottom navigation (design system `TabBar`): hairline top border, 24dp line icon over an 11.5sp label; the active icon is heavier and lifts slightly. */
@Composable
fun HarborBottomBar(items: List<HarborNavItem>) {
    // Keep full, readable labels for accessibility; a text-size increase must not
    // paint one navigation destination over its neighbour or clip a wrapped label.
    val largeText = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.4f
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 4.dp)) {
            items.forEach { item ->
                val tint = if (item.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                val lift = animateFloatAsState(if (item.selected) 1f else 0f, label = "tabLift").value
                Column(Modifier.weight(1f).heightIn(min = if (largeText) 96.dp else 56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick)
                    .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                    HarborIcon(item.glyph, tint, Modifier.graphicsLayer {
                        translationY = -2.dp.toPx() * lift
                        scaleX = 1f + 0.08f * lift
                        scaleY = 1f + 0.08f * lift
                    }, strokeWidth = if (item.selected) 2.3f else 1.8f)
                    Text(item.label, modifier = Modifier.fillMaxWidth(), color = tint,
                        fontSize = 11.5.sp, lineHeight = 14.sp, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}
