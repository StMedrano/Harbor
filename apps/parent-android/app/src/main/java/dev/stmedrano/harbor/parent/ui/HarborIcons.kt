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

internal fun circle(cx: Double, cy: Double, r: Double) =
    "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0"

internal fun roundRect(x: Double, y: Double, w: Double, h: Double, r: Double) =
    "M${x + r},$y h${w - 2 * r} a$r,$r 0 0 1 $r,$r v${h - 2 * r} a$r,$r 0 0 1 ${-r},$r " +
        "h${-(w - 2 * r)} a$r,$r 0 0 1 ${-r},${-r} v${-(h - 2 * r)} a$r,$r 0 0 1 $r,${-r} z"

/**
 * Harbor's own line icons (design system `Icon`): a 24-unit grid, 1.8 stroke, round caps and joins, drawn in one colour.
 * Navigation: map, alerts, controls, activity, family (parent); today, apps, about (child). Status icons share the grid.
 */
enum class HarborGlyph(val paths: List<String>) {
    MAP(listOf("M12 21s-7-6.2-7-11.5A7 7 0 0 1 19 9.5C19 14.8 12 21 12 21z", circle(12.0, 9.5, 2.5))),
    ALERTS(listOf("M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15z", "M10 20.5a2 2 0 0 0 4 0")),
    CONTROLS(listOf(roundRect(6.0, 3.0, 12.0, 18.0, 2.5), "M12 8v4l2.5 1.5")),
    ACTIVITY(listOf("M3 12h4l2.5-6 5 12 2.5-6h4")),
    FAMILY(listOf(circle(9.0, 8.0, 3.0), circle(17.0, 9.5, 2.3), "M3.5 19a5.5 5.5 0 0 1 11 0M14.5 19a4 4 0 0 1 6.5-3.1")),
    TODAY(listOf(circle(12.0, 12.0, 8.5), "M12 7.5V12l3 2")),
    APPS(listOf(roundRect(4.0, 4.0, 7.0, 7.0, 2.0), roundRect(13.0, 4.0, 7.0, 7.0, 2.0),
        roundRect(4.0, 13.0, 7.0, 7.0, 2.0), roundRect(13.0, 13.0, 7.0, 7.0, 2.0))),
    ABOUT(listOf("M12 21s-7-4.4-7-10V6l7-3 7 3v5c0 5.6-7 10-7 10z", "m9 12 2 2 4-4")),
    BATTERY(listOf(roundRect(3.0, 7.5, 16.0, 9.0, 2.5), "M21.5 10.5v3M6.5 10.5v3")),
    PHONE(listOf(roundRect(7.0, 2.5, 10.0, 19.0, 2.5), "M11 18.5h2")),
    CHECK(listOf(circle(12.0, 12.0, 8.5), "m8.5 12.2 2.4 2.4 4.8-5")),
    SEND(listOf("M20.5 3.5 3.5 10.5l7 3 3 7z", "m10.5 13.5 4-4")),
    HOURGLASS(listOf("M7 3.5h10M7 20.5h10M8 3.5V6l4 6 4-6V3.5M8 20.5V18l4-6 4 6v2.5")),
    EYE(listOf("M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12z", circle(12.0, 12.0, 3.0))),
    LOCK(listOf(roundRect(5.0, 10.5, 14.0, 10.0, 2.5), "M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5")),
    PLUS(listOf("M12 5v14M5 12h14")),
    ANCHOR(listOf(circle(12.0, 5.5, 2.0), "M12 7.5v13M8.5 11h7M4.5 13.5a7.5 7 0 0 0 15 0")),
    LIFEBUOY(listOf(circle(12.0, 12.0, 8.5), circle(12.0, 12.0, 3.5), "m6 6 3.5 3.5M14.5 14.5 18 18M18 6l-3.5 3.5M9.5 14.5 6 18")),
    LIGHTHOUSE(listOf("M9.5 21 10.5 9h3l1 12M9 9h6M10.5 9V6.5L12 4.5l1.5 2V9M5.5 6l3 1.2M18.5 6l-3 1.2M7 21h10")),
    // The child app's check-in tab uses the same circle-and-check as the status icon.
    CHECK_IN(CHECK.paths),
    // Account pages reached from Family, on the same grid.
    SETTINGS(listOf("M4 7h10M18 7h2M4 17h2M10 17h10", circle(16.0, 7.0, 2.0), circle(8.0, 17.0, 2.0))),
    SECURITY(ABOUT.paths),
    CHEVRON(listOf("m9.5 6 6 6-6 6")),
    CLOSE(listOf("M6.5 6.5l11 11M17.5 6.5l-11 11")),
    // Toast tone icons (design system `Toast`).
    TOAST_SUCCESS(listOf("M5 12.5l4.5 4.5L19 7.5")),
    TOAST_INFO(listOf(circle(12.0, 12.0, 8.5), "M12 11v5.5M12 7.6v.1")),
    TOAST_REVIEW(listOf("M12 4 21 19.5H3z", "M12 10v4.5M12 17.3v.1")),
    TOAST_URGENT(listOf(circle(12.0, 12.0, 8.5), "M12 7.5v5.5M12 16.3v.1")),
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
