package dev.stmedrano.harbor.parent.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The app bar's brand (design system `Brand`): the shield icon beside the name in brand colour. */
@Composable
fun HarborBrand(name: String = "Harbor Family", modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        HarborLogo(HarborLogoVariant.ICON, 26.dp, decorative = true)
        Text(name, style = HarborType.wordmark, color = harbor.brand)
    }
}

/** Design system `AppBar`: the brand on deep water over a hairline, with one optional action at the end. */
@Composable
fun HarborAppBar(action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(harbor.s)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HarborBrand(modifier = Modifier.weight(1f))
            action?.invoke()
        }
        HorizontalDivider(color = harbor.line)
    }
}

/** The uppercase kicker above a screen title, in sea glass. */
@Composable
fun HarborEyebrow(text: String, modifier: Modifier = Modifier, color: Color = harbor.acc) =
    Text(text.uppercase(), modifier, style = HarborType.eyebrow, color = color)

/**
 * Design system `ScreenHeader`: an eyebrow, a title in Harbor's voice, and a subtitle that says whose data it is and how
 * fresh ("Leo's Pixel 8", "Updated just now"). Sentence case, no exclamation marks.
 */
@Composable
fun HarborScreenHeader(eyebrow: String?, title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier.fillMaxWidth()) {
        if (eyebrow != null) HarborEyebrow(eyebrow)
        Text(title, Modifier.padding(top = if (eyebrow != null) 8.dp else 0.dp, bottom = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.headlineMedium, color = harbor.ink)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = harbor.ink2)
    }
}

data class HarborTab(val label: String, val selected: Boolean, val onClick: () -> Unit)

/** Segmented control (design system `Segmented`): a harbor-surface track; the selected option is tinted sea glass. */
@Composable
fun HarborTabs(tabs: List<HarborTab>, modifier: Modifier = Modifier) {
    val item = RoundedCornerShape(10.dp)
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(harbor.s2).border(1.dp, harbor.line, RoundedCornerShape(12.dp))
        .padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        tabs.forEach { tab ->
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(item)
                .background(if (tab.selected) harbor.accs else Color.Transparent)
                .selectable(selected = tab.selected, role = Role.Tab, onClick = tab.onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp), contentAlignment = Alignment.Center) {
                Text(tab.label, style = MaterialTheme.typography.labelMedium, color = if (tab.selected) harbor.acc else harbor.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A round initial in a crew colour (see [HarborKid]); deep-water initials. The colour identifies the person, never a status. */
@Composable
fun HarborAvatar(name: String, size: Dp = 36.dp, color: Color = HarborKid.color(0)) {
    Box(Modifier.size(size).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(name.trim().take(1).uppercase(), color = harbor.onColor, fontWeight = FontWeight.Bold,
            fontSize = if (size >= 40.dp) 15.sp else if (size >= 34.dp) 13.sp else 12.sp)
    }
}

/** Design system `Button` variants: primary (sea glass, the one main action), secondary (sea-glass outline), outline (quiet), danger (coral). */
enum class HarborButtonVariant { PRIMARY, SECONDARY, OUTLINE, DANGER }

/**
 * One button of the design system: 48dp tall (36dp [small], inside cards only), 12dp corners, a press that settles at
 * 96%. Disabled buttons fade instead of changing colour. [loading] shows a spinner and blocks repeat taps.
 */
@Composable
fun HarborActionButton(onClick: () -> Unit, variant: HarborButtonVariant, modifier: Modifier = Modifier, enabled: Boolean = true,
    small: Boolean = false, loading: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val c = harbor
    val (container, ink, border) = when (variant) {
        HarborButtonVariant.PRIMARY -> Triple(c.brand, c.bi, null)
        HarborButtonVariant.SECONDARY -> Triple(Color.Transparent, c.acc, BorderStroke(1.dp, c.acc))
        HarborButtonVariant.OUTLINE -> Triple(Color.Transparent, c.ink2, BorderStroke(1.dp, c.line))
        HarborButtonVariant.DANGER -> Triple(c.dan, c.onDan, null)
    }
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "press")
    val active = enabled && !loading
    Button(onClick, modifier.heightIn(min = if (small) 36.dp else 48.dp).graphicsLayer { scaleX = press; scaleY = press }
        .alpha(if (enabled || loading) 1f else 0.45f),
        enabled = active, shape = if (small) MaterialTheme.shapes.small else MaterialTheme.shapes.medium, border = border,
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = ink,
            disabledContainerColor = container, disabledContentColor = ink),
        contentPadding = if (small) PaddingValues(horizontal = 14.dp, vertical = 8.dp) else PaddingValues(horizontal = 18.dp, vertical = 12.dp),
        interactionSource = interaction) {
        CompositionLocalProvider(LocalTextStyle provides if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge) {
            if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = ink, strokeWidth = 2.dp)
            else content()
        }
    }
}

/** Primary action: sea glass, the one main action of a section. */
@Composable
fun HarborButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false, loading: Boolean = false,
    content: @Composable RowScope.() -> Unit) = HarborActionButton(onClick, HarborButtonVariant.PRIMARY, modifier, enabled, small, loading, content)

/** Secondary action: sea-glass outline and text. */
@Composable
fun HarborSecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false,
    content: @Composable RowScope.() -> Unit) = HarborActionButton(onClick, HarborButtonVariant.SECONDARY, modifier, enabled, small, content = content)

/** Quiet action: a hairline outline with muted text, such as Decline or Sync now. */
@Composable
fun HarborOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false,
    content: @Composable RowScope.() -> Unit) = HarborActionButton(onClick, HarborButtonVariant.OUTLINE, modifier, enabled, small, content = content)

/** Destructive action (for example removing a phone): coral, used only where the result is serious. */
@Composable
fun HarborDestructiveButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false,
    content: @Composable RowScope.() -> Unit) = HarborActionButton(onClick, HarborButtonVariant.DANGER, modifier, enabled, small, content = content)

/**
 * Design system `Card`: harbor surface with a hairline border and 18dp corners. An optional status [tag] (with a word,
 * never colour alone) and a heading sit above the content. [border] lets an urgent event mark its own card.
 */
@Composable
fun HarborCard(modifier: Modifier = Modifier, title: String? = null, tag: String? = null, tagTone: HarborTagTone = HarborTagTone.NEUTRAL,
    border: Color = harbor.line, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = harbor.s2, contentColor = harbor.ink, border = BorderStroke(1.dp, border),
        modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tag != null || title != null) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (tag != null) HarborTag(tag, tagTone)
                if (title != null) Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

/** A titled card: the heading names what the card holds ("Daily screen time", "Notifications"). */
@Composable
fun HarborPanel(title: String, content: @Composable ColumnScope.() -> Unit) = HarborCard(title = title, content = content)

enum class HarborTone { INFO, WARN, DANGER }

/**
 * Notes and banners. INFO is the calm sea-glass note, WARN is amber (review, stale data), DANGER is the coral banner
 * (errors, loss of access). The words always carry the tone too; colour is never the only signal.
 */
@Composable
fun HarborNote(tone: HarborTone = HarborTone.INFO, modifier: Modifier = Modifier, emphasis: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val c = harbor
    val (container, ink) = when (tone) {
        HarborTone.INFO -> c.accs to c.ink2
        HarborTone.WARN -> c.warns to c.warni
        HarborTone.DANGER -> c.dans to c.dan
    }
    Surface(shape = MaterialTheme.shapes.large, color = container, contentColor = ink, modifier = modifier.fillMaxWidth(),
        border = if (tone == HarborTone.DANGER) BorderStroke(1.dp, c.dan) else null) {
        CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.bodyMedium.copy(
            fontWeight = if (emphasis || tone == HarborTone.DANGER) FontWeight.SemiBold else FontWeight.Normal)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@Composable
fun HarborNote(text: String, tone: HarborTone = HarborTone.INFO, modifier: Modifier = Modifier, emphasis: Boolean = false) =
    HarborNote(tone, modifier, emphasis) { Text(text) }

/** HIGH = URGENT (coral), MEDIUM = REVIEW (amber), LOW = an update (sea glass), NEUTRAL = muted. */
enum class HarborTagTone { NEUTRAL, HIGH, MEDIUM, LOW }

/** Uppercase status tag (design system `Tag`): a word in its tone, such as REVIEW or ACTIVE. */
@Composable
fun HarborTag(text: String, tone: HarborTagTone = HarborTagTone.NEUTRAL, modifier: Modifier = Modifier) {
    val c = harbor
    val ink = when (tone) {
        HarborTagTone.NEUTRAL -> c.ink2
        HarborTagTone.HIGH -> c.dan
        HarborTagTone.MEDIUM -> c.warni
        HarborTagTone.LOW -> c.acc
    }
    Text(text.uppercase(), modifier, style = HarborType.tag, color = ink)
}

/** Unread count (design system `Badge`): hidden at 0, 99+ above 99. */
@Composable
fun HarborBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    Box(modifier.heightIn(min = 18.dp).widthIn(min = 18.dp).clip(CircleShape).background(harbor.dan).padding(horizontal = 5.dp)
        .clearAndSetSemantics { contentDescription = "$count new" }, contentAlignment = Alignment.Center) {
        Text(if (count > 99) "99+" else "$count", color = harbor.onDan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * One row of a card (design system `Row`): a bold title over a small secondary line, optional leading and trailing
 * content. Rows after the first draw a hairline above themselves.
 */
@Composable
fun HarborRow(title: String, modifier: Modifier = Modifier, subtitle: String? = null, divider: Boolean = false,
    onClick: (() -> Unit)? = null, leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth()) {
        if (divider) HorizontalDivider(color = harbor.line)
        Row(Modifier.fillMaxWidth().heightIn(min = if (onClick != null) 48.dp else 44.dp)
            .then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = harbor.ink)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
            }
            trailing?.invoke()
        }
    }
}

/** A trailing arrow for rows that open another page. */
@Composable
fun HarborChevron() = HarborIcon(HarborGlyph.CHEVRON, harbor.ink3, Modifier.size(20.dp))

/** Design system `Chip`: a pill that toggles; pressed chips are tinted sea glass. */
@Composable
fun HarborChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = harbor
    Box(modifier.heightIn(min = 40.dp).alpha(if (enabled) 1f else 0.45f).clip(CircleShape)
        .background(if (selected) c.accs else Color.Transparent)
        .border(1.dp, if (selected) c.acc else c.line, CircleShape)
        .selectable(selected = selected, enabled = enabled, role = Role.Checkbox, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) c.acc else c.ink2)
    }
}

/** Design system `Switch`: sea glass when on, a hairline track when off. */
@Composable
fun HarborSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = harbor
    Switch(checked, onCheckedChange, modifier, enabled = enabled, colors = SwitchDefaults.colors(
        checkedThumbColor = c.s, checkedTrackColor = c.acc, checkedBorderColor = c.acc,
        uncheckedThumbColor = c.ink, uncheckedTrackColor = c.line, uncheckedBorderColor = c.line,
        disabledCheckedThumbColor = c.s, disabledCheckedTrackColor = c.acc.copy(alpha = 0.45f),
        disabledUncheckedThumbColor = c.ink.copy(alpha = 0.45f), disabledUncheckedTrackColor = c.line.copy(alpha = 0.45f)))
}

/** A meter (design system `ProgressBar`): an 8dp sea-glass fill on deep water, amber once over the limit. */
@Composable
fun HarborMeter(fraction: Float, modifier: Modifier = Modifier, over: Boolean = false) {
    val c = harbor
    Box(modifier.fillMaxWidth().height(8.dp).clip(MaterialTheme.shapes.extraSmall).background(c.s)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).clip(MaterialTheme.shapes.extraSmall)
            .background(if (over) c.warn else c.acc))
    }
}

/** Field colours for text inputs: harbor surface, a hairline border, brand when focused. */
@Composable
internal fun harborFieldColors() = harbor.let { c ->
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = c.s2, unfocusedContainerColor = c.s2, disabledContainerColor = c.s2.copy(alpha = 0.5f),
        focusedBorderColor = c.brand, unfocusedBorderColor = c.line, disabledBorderColor = c.line.copy(alpha = 0.5f),
        focusedLabelColor = c.acc, unfocusedLabelColor = c.ink2, cursorColor = c.brand,
        focusedTextColor = c.ink, unfocusedTextColor = c.ink, errorBorderColor = c.dan, errorLabelColor = c.dan,
    )
}

data class HarborNavItem(val label: String, val glyph: HarborGlyph, val selected: Boolean, val badge: Int = 0, val onClick: () -> Unit)

/**
 * Bottom navigation (design system `TabBar`, floating dock): a rounded harbor-surface dock lifted off the bottom edge.
 * Each tab is Harbor's line icon over a 10sp label; the current tab sits in a sea-glass tint with a heavier stroke.
 */
@Composable
fun HarborBottomBar(items: List<HarborNavItem>) {
    val c = harbor
    Surface(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 14.dp), shape = MaterialTheme.shapes.extraLarge,
        color = c.s2, border = BorderStroke(1.dp, c.line)) {
        Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEach { item ->
                val tint = if (item.selected) c.acc else c.ink2
                Column(Modifier.weight(1f).heightIn(min = 52.dp).clip(MaterialTheme.shapes.medium)
                    .background(if (item.selected) c.accs else Color.Transparent)
                    .selectable(selected = item.selected, role = Role.Tab, onClick = item.onClick).padding(horizontal = 2.dp, vertical = 7.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Box {
                        HarborIcon(item.glyph, tint, Modifier.size(22.dp), strokeWidth = if (item.selected) 2.3f else 1.8f)
                        if (item.badge > 0) HarborBadge(item.badge, Modifier.align(Alignment.TopStart).offset(x = 15.dp, y = (-5).dp))
                    }
                    Text(item.label, color = tint, style = HarborType.nav,
                        fontWeight = if (item.selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}
