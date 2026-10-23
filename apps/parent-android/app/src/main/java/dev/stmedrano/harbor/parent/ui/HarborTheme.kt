package dev.stmedrano.harbor.parent.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * The Harbor Family design system's colour tokens (coastal theme, tokens v6). Names follow the design system's CSS names
 * (`s`, `s2`, `ink`, `brand`, `acc` …) so screens read the same words the design uses. Components never pick a theme
 * themselves: they read these, and [HarborTheme] provides Night Harbor or Daybreak.
 */
@Immutable
data class HarborColors(
    /** Open water: behind the app column on wide screens. */
    val bg: Color,
    /** Deep water: the screen, app bar and sheets. */
    val s: Color,
    /** Harbor surface: cards, the nav dock, inputs, segmented controls. */
    val s2: Color,
    /** Hairlines: card borders, row dividers, empty meter track. */
    val line: Color,
    val ink: Color,
    val ink2: Color,
    /** Quiet text: timestamps and small print. 4.5:1 on [s2] in both themes. */
    val ink3: Color,
    /** Primary action fill. */
    val brand: Color,
    /** Text and icons on [brand]. */
    val bi: Color,
    /** Sea glass: eyebrows, links, meters, switches, the active nav item. */
    val acc: Color,
    val accs: Color,
    /** Amber fill: review markers, over-limit meters, the focus ring. Never text in Daybreak: use [warni]. */
    val warn: Color,
    val warns: Color,
    /** Amber text. */
    val warni: Color,
    /** Alert coral: urgent only, always with a word. */
    val dan: Color,
    val dans: Color,
    val onDan: Color,
    val water: Color,
    val ripple: Color,
    val land: Color,
    val shore: Color,
    val road: Color,
    val pinRing: Color,
    val toastBg: Color,
    val toastBorder: Color,
    val scrim: Color,
    /** The wave on the shield glyph: the other theme's sea glass, so it reads against the ink shield. */
    val logoWave: Color,
    /** Night Harbor (true) or Daybreak. */
    val night: Boolean,
) {
    // The same in both themes.
    val toastInk get() = Color(0xFFF4F0E7)
    val toastSub get() = Color(0xFFABC0C3)
    val toastSuccess get() = Color(0xFF79D5C5)
    val toastInfo get() = Color(0xFF8DB8E8)
    val toastReview get() = Color(0xFFF2BD68)
    val toastUrgent get() = Color(0xFFF18C80)
    /** Deep-water initials on crew colours, pins and app tiles. */
    val onColor get() = Color(0xFF0B2027)
    /** The lighthouse lamp, amber in every theme. */
    val logoLamp get() = Color(0xFFF2BD68)
}

/** Night Harbor, the primary theme: deep navy water, harbor-surface cards, sea-glass teal, warm cream text. */
val NightHarbor = HarborColors(
    bg = Color(0xFF071A20), s = Color(0xFF0B2027), s2 = Color(0xFF15333C), line = Color(0xFF24444C),
    ink = Color(0xFFF4F0E7), ink2 = Color(0xFFABC0C3), ink3 = Color(0xFF8AA3A7),
    brand = Color(0xFF79D5C5), bi = Color(0xFF0B2027), acc = Color(0xFF79D5C5), accs = Color(0x1A79D5C5),
    warn = Color(0xFFF2BD68), warns = Color(0x24F2BD68), warni = Color(0xFFF2BD68),
    // dans is rgba(241,140,128,0.14); its 8-bit alpha rounds down so coral text on it keeps 4.5:1.
    dan = Color(0xFFF18C80), dans = Color(0x23F18C80), onDan = Color(0xFF0B2027),
    water = Color(0xFF102F3A), ripple = Color(0x1C79D5C5), land = Color(0xFF284947), shore = Color(0xFF75B1A0),
    road = Color(0xFF3A6A68), pinRing = Color(0xFFF4F0E7),
    toastBg = Color(0xFF1C3F48), toastBorder = Color(0x3879D5C5), scrim = Color(0x99040C0F),
    logoWave = Color(0xFF1F6B5C), night = true,
)

/** Daybreak: the same harbor at first light, with a deeper teal so actions keep their contrast. */
val Daybreak = HarborColors(
    bg = Color(0xFFEDF0EB), s = Color(0xFFFFFFFF), s2 = Color(0xFFF4F0E7), line = Color(0xFFD7DED7),
    ink = Color(0xFF0B2027), ink2 = Color(0xFF526762), ink3 = Color(0xFF48746D),
    brand = Color(0xFF15333C), bi = Color(0xFFF4F0E7), acc = Color(0xFF1F6B5C), accs = Color(0xFFDCEFEA),
    warn = Color(0xFFC98A2A), warns = Color(0xFFFBECD2), warni = Color(0xFF7A4D08),
    dan = Color(0xFFB4382C), dans = Color(0xFFF6DCD7), onDan = Color(0xFFFFFFFF),
    water = Color(0xFFCFE3E3), ripple = Color(0x1F1F6B5C), land = Color(0xFFE4E9E1), shore = Color(0xFF9CC2B6),
    road = Color(0xFFFFFFFF), pinRing = Color(0xFFFFFFFF),
    toastBg = Color(0xFF0B2027), toastBorder = Color(0xFF0B2027), scrim = Color(0x730B2027),
    logoWave = Color(0xFF79D5C5), night = false,
)

val LocalHarborColors = staticCompositionLocalOf { NightHarbor }

/** The current theme's tokens. */
val harbor: HarborColors
    @Composable @ReadOnlyComposable get() = LocalHarborColors.current

/** How Harbor picks its theme: with the phone's day/night setting, or one chosen in Settings. */
enum class HarborAppearance(val label: String) { SYSTEM("Match phone"), NIGHT("Night Harbor"), DAYBREAK("Daybreak") }

/** The appearance chosen in Settings, kept on this phone only. */
object HarborAppearanceStore {
    private const val PREFS = "harbor_appearance"
    private const val KEY = "mode"
    var mode by mutableStateOf(HarborAppearance.SYSTEM)
        private set

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        mode = HarborAppearance.entries.firstOrNull { it.name == saved } ?: HarborAppearance.SYSTEM
    }

    fun choose(context: Context, appearance: HarborAppearance) {
        mode = appearance
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, appearance.name).apply()
    }
}

/** True when Harbor should draw Night Harbor. */
@Composable
fun harborNight(): Boolean = when (HarborAppearanceStore.mode) {
    HarborAppearance.SYSTEM -> isSystemInDarkTheme()
    HarborAppearance.NIGHT -> true
    HarborAppearance.DAYBREAK -> false
}

@Composable
fun HarborTheme(dark: Boolean = harborNight(), content: @Composable () -> Unit) {
    val c = if (dark) NightHarbor else Daybreak
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        // Status and navigation bar icons follow Harbor's theme, which can differ from the phone's when chosen in Settings.
        view.context.findActivity()?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalHarborColors provides c) {
        MaterialTheme(colorScheme = materialColors(c), typography = harborTypography(), shapes = HarborShapes, content = content)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Material colours mapped onto Harbor's tokens, so stock Material parts (dialogs, chips, fields) take the theme too. */
private fun materialColors(c: HarborColors): ColorScheme {
    val base = if (c.night) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.brand, onPrimary = c.bi, primaryContainer = c.accs, onPrimaryContainer = c.acc, inversePrimary = c.acc,
        secondary = c.acc, onSecondary = c.s, secondaryContainer = c.accs, onSecondaryContainer = c.acc,
        tertiary = c.warn, onTertiary = c.onColor, tertiaryContainer = c.warns, onTertiaryContainer = c.warni,
        error = c.dan, onError = c.onDan, errorContainer = c.dans, onErrorContainer = c.dan,
        background = c.s, onBackground = c.ink, surface = c.s, onSurface = c.ink,
        surfaceVariant = c.s2, onSurfaceVariant = c.ink2, surfaceTint = c.s,
        inverseSurface = c.toastBg, inverseOnSurface = c.toastInk,
        outline = c.line, outlineVariant = c.line, scrim = c.scrim,
        surfaceBright = c.s, surfaceDim = c.bg,
        surfaceContainerLowest = c.s, surfaceContainerLow = c.s, surfaceContainer = c.s2,
        surfaceContainerHigh = c.s2, surfaceContainerHighest = c.s2,
    )
}

/** Radii: 5 meters, 10 small buttons and segments, 12 buttons and inputs, 18 cards, 22 the nav dock and sheets. */
val HarborShapes = Shapes(
    extraSmall = RoundedCornerShape(5.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp), extraLarge = RoundedCornerShape(22.dp),
)

/** The system sans: no fonts to download, and every script the phone supports. */
val HarborFont: FontFamily = FontFamily.Default

/** The design system's text styles that have no Material slot. */
object HarborType {
    /** Uppercase kicker above every screen title, in sea glass. */
    val eyebrow = TextStyle(fontFamily = HarborFont, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
    /** Uppercase status tags (REVIEW, URGENT). */
    val tag = TextStyle(fontFamily = HarborFont, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    /** Nav labels under the icons. */
    val nav = TextStyle(fontFamily = HarborFont, fontSize = 10.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    /** The name in the app bar. */
    val wordmark = TextStyle(fontFamily = HarborFont, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.Bold)
    /** Toast titles: what happened, in one line. */
    val toastTitle = TextStyle(fontFamily = HarborFont, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
}

/** Design-system `metric`: the big number on a card, tabular so digits do not jitter. A trailing unit drops to 16sp. */
val HarborMetricStyle = TextStyle(fontFamily = HarborFont, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight(650),
    fontFeatureSettings = "tnum")

/** Design-system `code`: a six-digit pairing code, wide-tracked, grouped 3 + 3. */
val HarborCodeStyle = TextStyle(fontFamily = HarborFont, fontSize = 32.sp, lineHeight = 38.sp, fontWeight = FontWeight(650),
    letterSpacing = 0.18.em, fontFeatureSettings = "tnum")

/**
 * Crew colours `kid-1` to `kid-6` (sky, lilac, sand, moss, orchid, stone), handed out in the order children were added.
 * They identify a person only and are never a status colour. Initials on them are deep water ([HarborColors.onColor]).
 */
object HarborKid {
    val colors = listOf(Color(0xFF8DB8E8), Color(0xFFC3A6E6), Color(0xFFD9C49A), Color(0xFFB7C97A), Color(0xFFE0A9D6), Color(0xFFC4C0B6))
    fun color(index: Int): Color = colors[Math.floorMod(index, colors.size)]
}

/** Parents on the family map: sea glass, so a parent's pin never takes a child's crew colour. Deep-water initials. */
val HarborParentColor = Color(0xFF79D5C5)

// Type scale: title 28/34 bold (−0.7), sheet title 20/26, heading 16/22 bold, body 16/24, small body 14/21,
// label 12/18, button 14 semibold, small button 13 semibold, nav 10.
private fun harborTypography(): Typography {
    val base = Typography()
    fun TextStyle.harbor() = copy(fontFamily = HarborFont)
    val title = base.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp).harbor()
    return base.copy(
        displayLarge = base.displayLarge.harbor(), displayMedium = base.displayMedium.harbor(),
        displaySmall = base.displaySmall.copy(fontSize = 48.sp, lineHeight = 53.sp, fontWeight = FontWeight.Bold, letterSpacing = (-2).sp).harbor(),
        headlineLarge = title, headlineMedium = title, headlineSmall = title,
        titleLarge = base.titleLarge.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp).harbor(),
        titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.sp).harbor(),
        titleSmall = base.titleSmall.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).harbor(),
        bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp).harbor(),
        bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp, letterSpacing = 0.sp).harbor(),
        bodySmall = base.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp, letterSpacing = 0.sp).harbor(),
        labelLarge = base.labelLarge.copy(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).harbor(),
        labelMedium = base.labelMedium.copy(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp).harbor(),
        labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp).harbor(),
    )
}
