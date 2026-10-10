package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

private fun circle(cx: Double, cy: Double, r: Double) =
    "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

private fun roundRect(x: Double, y: Double, w: Double, h: Double, r: Double) =
    "M${x + r},$y h${w - 2 * r} a$r,$r 0 0 1 $r,$r v${h - 2 * r} a$r,$r 0 0 1 ${-r},$r " +
        "h${-(w - 2 * r)} a$r,$r 0 0 1 ${-r},${-r} v${-(h - 2 * r)} a$r,$r 0 0 1 $r,${-r} z"

/** Line icons on the same 24-unit grid and 1.8 stroke as the web app's tab icons (web/harbor-family/js). */
enum class HarborGlyph(val paths: List<String>) {
    FAMILY(listOf(circle(9.0, 8.0, 3.0), "M3.5 19v-1a5.5 5.5 0 0 1 11 0v1", circle(17.0, 9.0, 2.4), "M16 14.2a4.6 4.6 0 0 1 5 4.3V19")),
    SETTINGS(listOf("M4 7h10M18 7h2M4 17h2M10 17h10", circle(16.0, 7.0, 2.0), circle(8.0, 17.0, 2.0))),
    SECURITY(listOf("M12 21s-7-4.4-7-10V6l7-3 7 3v5c0 5.6-7 10-7 10z", "m9 12 2 2 4-4")),
    TODAY(listOf(circle(12.0, 12.0, 8.5), "M12 7.5V12l3 2")),
    APPS(listOf(roundRect(4.0, 4.0, 7.0, 7.0, 2.0), roundRect(13.0, 4.0, 7.0, 7.0, 2.0),
        roundRect(4.0, 13.0, 7.0, 7.0, 2.0), roundRect(13.0, 13.0, 7.0, 7.0, 2.0))),
    ABOUT(listOf("M12 21s-7-4.4-7-10V6l7-3 7 3v5c0 5.6-7 10-7 10z", "m9 12 2 2 4-4")),
}

@Composable
fun HarborIcon(glyph: HarborGlyph, tint: Color, modifier: Modifier = Modifier, strokeWidth: Float = 1.8f) {
    val parsed = remember(glyph) { glyph.paths.map { PathParser().parsePathString(it).toPath() } }
    // Decorative: the label next to the icon carries the meaning for TalkBack.
    Canvas(modifier.size(24.dp)) {
        scale(size.width / 24f, pivot = Offset.Zero) {
            parsed.forEach { drawPath(it, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}
