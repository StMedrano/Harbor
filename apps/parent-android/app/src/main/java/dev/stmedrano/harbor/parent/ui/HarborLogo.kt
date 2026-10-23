package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Harbor Family logo: a lighthouse in a shield, with a family on the pier below it. Three levels of detail:
 * [LOCKUP] (scene and name; splash, sign-in, About; at least 140dp wide), [MARK] (the scene without the name; above a
 * title that already names the app; at least 80dp) and [ICON] (shield, lighthouse, lamp and wave; the app bar at 26dp).
 */
enum class HarborLogoVariant(val viewX: Float, val viewY: Float, val viewW: Float, val viewH: Float) {
    LOCKUP(0f, 0f, 300f, 316f), MARK(40f, 12f, 224f, 212f), ICON(0f, 0f, 64f, 64f),
}

/**
 * Draws the logo in the current theme's colours: ink for the shield, tower, name, one parent and one child; sea glass for
 * the pier, the other parent and child and FAMILY; the amber lamp in both themes. [ground] is the surface behind it, used
 * for the tower stripes and the shield's openings. The light beams are static. [tile] puts the icon on its app-icon tile.
 */
@Composable
fun HarborLogo(variant: HarborLogoVariant, width: Dp, modifier: Modifier = Modifier, ground: Color = harbor.s,
    tile: Boolean = false, decorative: Boolean = false) {
    val c = harbor
    val height = width * (variant.viewH / variant.viewW)
    val described = if (decorative) Modifier.clearAndSetSemantics {} else Modifier.semantics { contentDescription = "Harbor Family" }
    Canvas(modifier.size(width, height).then(described)) {
        val unit = size.width / variant.viewW
        withTransform({ scale(unit, unit, pivot = Offset.Zero); translate(-variant.viewX, -variant.viewY) }) {
            when (variant) {
                HarborLogoVariant.ICON -> drawIcon(c, ground, tile)
                HarborLogoVariant.MARK -> drawScene(c, ground)
                HarborLogoVariant.LOCKUP -> { drawScene(c, ground); drawName(c) }
            }
        }
    }
}

private object LogoPaths {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()
    private fun box(x: Double, y: Double, w: Double, h: Double) = "M$x,$y h$w v$h h${-w} z"

    val below = p("M0 0H300V166H258C244 176 232 181 220 187C190 201 150 203 120 193C92 183 66 179 44 176L0 176Z")
    val tower = p("M79.5 89H96.5L103 178H73Z")
    val shield = p("M150 20 242 46V138C242 154 232 168 214 178C190 190 168 194 150 202C132 194 110 190 86 178C68 168 58 154 58 138V46Z")
    val beams = p("M79.5 78.5 67 71V91L79.5 83.5Z M96.5 78.5 120 70V92L96.5 83.5Z")
    val lantern = p(circle(88.0, 62.5, 2.3) + box(87.2, 63.5, 1.6, 3.0) + "M78.5 76C78.5 69 82.5 66 88 66S97.5 69 97.5 76Z" +
        roundRect(74.5, 85.0, 27.0, 4.2, 1.6))
    val lamp = p(box(79.5, 76.0, 17.0, 9.0))
    val mullions = p("M85.2 76V85M90.8 76V85")
    val stripes = p("M60 102 120 84V95L60 113Z M60 124 120 106V117L60 135Z M60 146 120 128V139L60 157Z M60 168 120 150V161L60 179Z")
    val pier = p(box(118.0, 144.0, 108.0, 60.0))
    val deck = p(roundRect(116.0, 140.0, 112.0, 4.5, 1.5))
    val arches = p("M128 196V163a7 7 0 0 1 14 0V196Z M151 196V163a7 7 0 0 1 14 0V196Z M174 196V163a7 7 0 0 1 14 0V196Z M197 196V163a7 7 0 0 1 14 0V196Z")
    val inkFamily = p(circle(158.0, 85.0, 6.5) + roundRect(151.0, 94.0, 14.0, 26.0, 6.0) + roundRect(152.0, 112.0, 5.6, 28.0, 2.6) +
        roundRect(158.4, 112.0, 5.6, 28.0, 2.6) + circle(135.0, 106.0, 4.8) + roundRect(131.0, 112.0, 8.0, 16.0, 3.6) +
        roundRect(131.6, 124.0, 3.0, 16.0, 1.5) + roundRect(135.4, 124.0, 3.0, 16.0, 1.5))
    val inkArms = p("M163 99 174 110M153 99 143 112")
    val inkHand = p("M139 115 143 112")
    val accFamily = p(circle(190.0, 87.0, 6.2) + roundRect(185.5, 121.0, 3.6, 19.0, 1.8) + roundRect(191.0, 121.0, 3.6, 19.0, 1.8) +
        circle(213.0, 107.0, 4.6) + roundRect(209.0, 127.0, 3.0, 13.0, 1.5) + roundRect(214.0, 127.0, 3.0, 13.0, 1.5))
    val accDress = p("M184 97H196L200.5 121H179.5Z")
    val accSkirt = p("M208.5 114H217.5L220.5 128H205.5Z")
    val accArms = p("M184 101 176.5 110.5M196 101 206 112")
    val accHand = p("M208.5 116 206 112")
    val shore = p("M44 176C70 162 96 170 122 180C150 190 190 186 216 172C228 165 244 163 258 166C244 176 232 181 220 187C190 201 150 203 120 193C92 183 66 179 44 176Z")
    val sea = p("M98 199C130 211 190 211 236 188C218 209 170 220 132 215C114 212 104 207 98 199Z")
    // HARBOR and FAMILY are outlines in the design system's lockup file, so the name looks the same on every phone.
    val harborWord = p("M38.49 274H46.83V256.64H64.58V274H72.94V233.26H64.58V249.72H46.83V233.26H38.49ZM76.56 274H85.78L88.81 264.54H103.28L106.42 274H115.69L101.42 233.26H90.48ZM90.89 258 92.07 254.34C93.3 250.24 94.5 245.4 95.87 239.79C97.29 245.34 98.57 250.16 99.89 254.34L101.12 258ZM119.34 274H127.68V259.45H134.24L142 274H151.25L142.58 258.11C147.23 256.12 149.8 252.1 149.8 246.52C149.8 238.51 144.49 233.26 135.22 233.26H119.34ZM127.68 252.7V240.15H133.66C138.78 240.15 141.18 242.42 141.18 246.52C141.18 250.59 138.78 252.7 133.69 252.7ZM154.71 274H171.91C181.1 274 185.91 269.21 185.91 262.76C185.91 256.72 181.59 253.11 177 252.89V252.48C181.18 251.55 184.32 248.57 184.32 243.73C184.32 237.63 179.81 233.26 170.87 233.26H154.71ZM163.05 267.14V256.09H170.49C174.67 256.09 177.32 258.55 177.32 261.97C177.32 265.06 175.16 267.14 170.27 267.14ZM163.05 250.38V240.07H169.8C173.71 240.07 175.87 242.09 175.87 245.07C175.87 248.35 173.22 250.38 169.64 250.38ZM208.23 274.55C219.03 274.55 227.24 266.92 227.24 253.66C227.24 240.34 219.03 232.71 208.23 232.71C197.43 232.71 189.23 240.34 189.23 253.66C189.23 266.89 197.43 274.55 208.23 274.55ZM208.23 267.14C201.92 267.14 197.71 262.38 197.71 253.66C197.71 244.91 201.92 240.12 208.23 240.12C214.55 240.12 218.76 244.88 218.76 253.66C218.76 262.38 214.55 267.14 208.23 267.14ZM232.1 274H240.44V259.45H247L254.77 274H264.01L255.34 258.11C259.99 256.12 262.56 252.1 262.56 246.52C262.56 238.51 257.26 233.26 247.99 233.26H232.1ZM240.44 252.7V240.15H246.43C251.54 240.15 253.95 242.42 253.95 246.52C253.95 250.59 251.54 252.7 246.46 252.7Z")
    val familyWord = p("M101.23 306H104.51V299.86H111.05V297.18H104.51V292.71H111.76V289.99H101.23ZM115.17 306H118.79L119.98 302.28H125.67L126.9 306H130.54L124.94 289.99H120.64ZM120.8 299.72 121.26 298.28C121.74 296.67 122.22 294.76 122.75 292.56C123.31 294.74 123.82 296.63 124.33 298.28L124.82 299.72ZM136.48 306H139.74V298.45C139.74 297.25 139.65 294.89 139.62 292.95C140.19 295.1 140.84 297.34 141.21 298.45L143.91 306H146.67L149.32 298.45C149.7 297.31 150.37 294.98 150.91 292.82C150.88 294.84 150.79 297.25 150.79 298.45V306H154.09V289.99H149.04L146.49 297.34C146.16 298.36 145.67 300.5 145.3 302.23C144.93 300.49 144.43 298.35 144.11 297.34L141.51 289.99H136.48ZM164.21 289.99H160.94V306H164.21ZM171.08 306H181.26V303.28H174.36V289.99H171.08ZM190.57 306H193.83V299.98L199.67 289.99H195.84L193.22 294.98C192.85 295.7 192.53 296.36 192.22 297.21C191.9 296.38 191.59 295.71 191.18 294.98L188.47 289.99H184.64L190.57 299.98Z")
    val nameWaves = p("M47.8 298c5-5 9 3 14-1s9-5 13-1 9-3 13 0M213.2 298c5-5 9 3 13-1s9-5 13-1 9 3 14 0")

    val tileShape = p(roundRect(0.0, 0.0, 64.0, 64.0, 14.0))
    val iconShield = p("M32 4 56 11.5V33C56 47 46 56 32 61 18 56 8 47 8 33V11.5Z")
    val iconOpenings = p("M26.5 51 28.5 26h7l2 25z M26.2 18h11.6L32 12z")
    val iconLamp = p(roundRect(27.5, 18.0, 9.0, 7.0, 1.2))
    val iconWave = p("M12.5 49c6-4.5 12 2 19.5-1s12.5-4 19.5-1")
}

private fun DrawScope.drawScene(c: HarborColors, ground: Color) {
    val round = StrokeCap.Round
    clipPath(LogoPaths.below) {
        drawPath(LogoPaths.shield, c.ink, style = Stroke(5.5f, join = StrokeJoin.Round))
        translate(7f, 0f) {
            drawPath(LogoPaths.beams, c.logoLamp, alpha = if (c.night) 0.75f else 0.5f)
            drawPath(LogoPaths.lantern, c.ink)
            drawPath(LogoPaths.lamp, c.logoLamp)
            drawPath(LogoPaths.mullions, c.ink, style = Stroke(1.5f))
            drawPath(LogoPaths.tower, ground)
            clipPath(LogoPaths.tower) { drawPath(LogoPaths.stripes, c.ink) }
            drawPath(LogoPaths.tower, c.ink, style = Stroke(3f, join = StrokeJoin.Round))
        }
        drawPath(LogoPaths.pier, c.acc)
        drawPath(LogoPaths.deck, c.ink)
        drawPath(LogoPaths.arches, c.ink)
        drawPath(LogoPaths.inkFamily, c.ink)
        drawPath(LogoPaths.inkArms, c.ink, style = Stroke(3.6f, cap = round))
        drawPath(LogoPaths.inkHand, c.ink, style = Stroke(3f, cap = round))
        drawPath(LogoPaths.accFamily, c.acc)
        listOf(LogoPaths.accDress to 3f, LogoPaths.accSkirt to 2.4f).forEach { (shape, width) ->
            drawPath(shape, c.acc)
            drawPath(shape, c.acc, style = Stroke(width, cap = round, join = StrokeJoin.Round))
        }
        drawPath(LogoPaths.accArms, c.acc, style = Stroke(3.4f, cap = round))
        drawPath(LogoPaths.accHand, c.acc, style = Stroke(3f, cap = round))
    }
    drawPath(LogoPaths.shore, c.ink)
    drawPath(LogoPaths.sea, c.acc)
}

private fun DrawScope.drawName(c: HarborColors) {
    drawPath(LogoPaths.harborWord, c.ink)
    drawPath(LogoPaths.familyWord, c.acc)
    drawPath(LogoPaths.nameWaves, c.acc, style = Stroke(2.6f, cap = StrokeCap.Round))
}

private fun DrawScope.drawIcon(c: HarborColors, ground: Color, tile: Boolean) {
    if (tile) {
        drawPath(LogoPaths.tileShape, ground)
        drawPath(LogoPaths.tileShape, c.line, style = Stroke(0.8f))
    }
    val glyphScale = if (tile) 0.9f else 1f
    scale(glyphScale, pivot = Offset(32f, 32.5f)) {
        drawPath(LogoPaths.iconShield, c.ink)
        drawPath(LogoPaths.iconOpenings, ground)
        drawPath(LogoPaths.iconLamp, c.logoLamp)
        drawPath(LogoPaths.iconWave, c.logoWave, style = Stroke(3.8f, cap = StrokeCap.Round))
    }
}

/** The four empty-state illustrations (design system `HarborArt`), chosen by meaning. They never move. */
enum class HarborArt { LIGHTHOUSE, BUOY, ANCHOR, CALM }

private object ArtPaths {
    private fun p(d: String): Path = PathParser().parsePathString(d).toPath()
    val water1 = p("M6 74c8-4 16-4 24 0s16 4 24 0 16-4 24 0 16 4 24 0 8-2 10-2")
    val water2 = p("M20 84c8-3 14-3 20 0s14 3 20 0 14-3 20 0 14 3 20 0")
    val glow = p(circle(60.0, 40.0, 30.0))
    val beams = p("M60 26 22 14v20z M60 26l38-12v20z")
    val tower = p("M52 70 55 32h10l3 38z")
    val towerStripes = p("M54 46h12M53 58h14")
    val lamp = p(roundRect(54.0, 22.0, 12.0, 10.0, 2.0))
    val roof = p("M52 22h16L60 15z")
    val rock = p("M40 72c6-5 34-5 40 0")
    val buoyRing1 = p(circle(60.0, 66.0, 24.0))
    val buoyRing2 = p(circle(60.0, 66.0, 16.0))
    val buoy = p("M50 66 54 38h12l4 28z")
    val buoyStripe = p("M52.5 50h15")
    val mast = p("M60 38V26")
    val buoyLight = p(circle(60.0, 23.0, 3.5))
    val buoyWater = p("M42 68c6 3 12 3 18 0s12-3 18 0")
    val anchorGlow = p(circle(60.0, 44.0, 30.0))
    val anchorRing = p(circle(60.0, 24.0, 5.0))
    val anchor = p("M60 29v36M50 37h20M40 50c2 10 10 16 20 16s18-6 20-16")
    val flukes = p("M36 54l4-5 5 3M84 54l-4-5-5 3")
    val moon = p(circle(78.0, 28.0, 12.0))
    val moonShadow = p(circle(84.0, 24.0, 10.0))
    val stars = p(circle(32.0, 22.0, 1.6) + circle(48.0, 34.0, 1.2) + circle(22.0, 40.0, 1.2))
    val boat = p("M38 66h28l-5 6H43zM52 66V48l10 14H52")
}

@Composable
fun HarborArtwork(art: HarborArt, modifier: Modifier = Modifier, width: Dp = 120.dp) {
    val c = harbor
    Canvas(modifier.size(width, width * (92f / 120f)).clearAndSetSemantics {}) {
        scale(size.width / 120f, pivot = Offset.Zero) {
            val line = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val thick = Stroke(3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun fillAndLine(path: Path, fill: Color, ink: Color) { drawPath(path, fill); drawPath(path, ink, style = line) }
            fun water(both: Boolean) {
                if (both) drawPath(ArtPaths.water1, c.acc, alpha = 0.55f, style = line)
                drawPath(ArtPaths.water2, c.acc, alpha = 0.3f, style = line)
            }
            when (art) {
                HarborArt.LIGHTHOUSE -> {
                    drawPath(ArtPaths.glow, c.accs)
                    drawPath(ArtPaths.beams, c.warn, alpha = 0.18f)
                    fillAndLine(ArtPaths.tower, c.s2, c.ink2)
                    drawPath(ArtPaths.towerStripes, c.dan, alpha = 0.85f, style = thick)
                    fillAndLine(ArtPaths.lamp, c.warn, c.ink2)
                    fillAndLine(ArtPaths.roof, c.s2, c.ink2)
                    fillAndLine(ArtPaths.rock, c.land, c.shore)
                    water(true)
                }
                HarborArt.BUOY -> {
                    drawPath(ArtPaths.buoyRing1, c.acc, alpha = 0.18f, style = line)
                    drawPath(ArtPaths.buoyRing2, c.acc, alpha = 0.32f, style = line)
                    fillAndLine(ArtPaths.buoy, c.s2, c.ink2)
                    drawPath(ArtPaths.buoyStripe, c.warn, style = Stroke(4f, cap = StrokeCap.Round))
                    drawPath(ArtPaths.mast, c.ink2, style = line)
                    drawPath(ArtPaths.buoyLight, c.warn)
                    drawPath(ArtPaths.buoyWater, c.acc, style = line)
                    water(false)
                }
                HarborArt.ANCHOR -> {
                    drawPath(ArtPaths.anchorGlow, c.accs)
                    drawPath(ArtPaths.anchorRing, c.acc, style = thick)
                    drawPath(ArtPaths.anchor, c.acc, style = thick)
                    drawPath(ArtPaths.flukes, c.acc, style = thick)
                    water(true)
                }
                HarborArt.CALM -> {
                    clipPath(ArtPaths.moonShadow, ClipOp.Difference) { drawPath(ArtPaths.moon, c.warn, alpha = 0.9f) }
                    drawPath(ArtPaths.stars, c.ink3)
                    fillAndLine(ArtPaths.boat, c.s2, c.ink2)
                    water(true)
                }
            }
        }
    }
}

/**
 * An empty list (design system `EmptyState`): an illustration by meaning (lighthouse all clear, buoy waiting, anchor
 * setup needed, calm a quiet day), what will appear here and, only when the person has to act, one primary action.
 */
@Composable
fun HarborEmptyState(title: String, modifier: Modifier = Modifier, art: HarborArt? = HarborArt.LIGHTHOUSE, body: String? = null,
    action: String? = null, onAction: (() -> Unit)? = null, actionEnabled: Boolean = true) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (art != null) HarborArtwork(art, Modifier.padding(bottom = 4.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = harbor.ink, textAlign = TextAlign.Center)
        if (body != null) Text(body, Modifier.widthIn(max = 300.dp), style = MaterialTheme.typography.bodyMedium, color = harbor.ink2,
            textAlign = TextAlign.Center)
        if (action != null && onAction != null) HarborButton(onAction, Modifier.padding(top = 10.dp), enabled = actionEnabled) { Text(action) }
    }
}
