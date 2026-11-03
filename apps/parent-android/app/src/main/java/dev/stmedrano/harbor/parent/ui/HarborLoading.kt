package dev.stmedrano.harbor.parent.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The design system's `--dur-loading`: one full sweep of the beam, and one wave length of the water. */
private const val LOADING_MS = 2400

/**
 * The design system's loader (`hb-ld` in harbor.css): a lighthouse whose two beams sweep in turn over moving water, drawn
 * on the same 120 by 88 grid. With animations turned off on the phone it holds still, and the words still say what is
 * happening.
 */
@Composable
fun HarborLoader(width: Dp = 120.dp, modifier: Modifier = Modifier) {
    val c = harbor
    val reduced = rememberReducedMotion()
    val motion = rememberInfiniteTransition(label = "harbor-loader")
    val sweep = keyframes<Float> {
        durationMillis = LOADING_MS
        0f at 0
        1f at LOADING_MS / 2
        0f at LOADING_MS
    }
    val left by motion.animateFloat(0f, 1f, infiniteRepeatable(sweep, RepeatMode.Restart), label = "left-beam")
    val flow by motion.animateFloat(0f, 24f, infiniteRepeatable(tween(LOADING_MS, easing = LinearEasing), RepeatMode.Restart), label = "water")
    Canvas(modifier.size(width, width * (88f / 120f)).clearAndSetSemantics {}) {
        val u = size.width / 120f
        // The right beam runs half a sweep behind the left, so one is always rising as the other falls.
        val leftOpen = if (reduced) 0.7f else left
        val rightOpen = if (reduced) 0.7f else (1f - left)
        fun beamShape(fromX: Float, toX: Float) = Path().apply {
            moveTo(fromX * u, 38.5f * u); lineTo(toX * u, 27f * u); lineTo(toX * u, 50f * u); close()
        }
        withTransform({ scale(0.12f + 0.88f * leftOpen, 1f, pivot = Offset(52f * u, 38.5f * u)) }) {
            drawPath(beamShape(52f, 8f), c.logoLamp, alpha = 0.75f * leftOpen)
        }
        withTransform({ scale(0.12f + 0.88f * rightOpen, 1f, pivot = Offset(68f * u, 38.5f * u)) }) {
            drawPath(beamShape(68f, 112f), c.logoLamp, alpha = 0.75f * rightOpen)
        }
        // Tower, its stripes, the lamp room and the roof.
        drawPath(Path().apply {
            moveTo(54f * u, 44f * u); lineTo(66f * u, 44f * u); lineTo(70f * u, 76f * u); lineTo(50f * u, 76f * u); close()
        }, c.ink)
        listOf(52f, 64f).forEach { y ->
            drawPath(Path().apply {
                moveTo(51f * u, (y + 3f) * u); lineTo(69f * u, (y - 2f) * u); lineTo(69f * u, (y + 3f) * u); lineTo(51f * u, (y + 8f) * u); close()
            }, c.s)
        }
        drawRect(c.logoLamp, Offset(54f * u, 34f * u), Size(12f * u, 9f * u))
        drawRect(c.ink, Offset(52f * u, 43f * u), Size(16f * u, 2.5f * u))
        drawPath(Path().apply {
            moveTo(52f * u, 34f * u); lineTo(60f * u, 26f * u); lineTo(68f * u, 34f * u); close()
        }, c.ink)
        // Two lines of water, moving opposite ways, each repeating every 24 units so the loop has no seam.
        clipRect(0f, 0f, size.width, size.height) {
            fun water(y: Float) = Path().apply {
                var x = -48f
                moveTo(x * u, y * u)
                while (x < 144f) {
                    quadraticTo((x + 6f) * u, (y - 4f) * u, (x + 12f) * u, y * u)
                    quadraticTo((x + 18f) * u, (y + 4f) * u, (x + 24f) * u, y * u)
                    x += 24f
                }
            }
            val shift = if (reduced) 0f else flow
            val line = Stroke(width = 2.6f * u, cap = StrokeCap.Round)
            withTransform({ translate(shift * u, 0f) }) { drawPath(water(77f), c.acc, style = line) }
            withTransform({ translate(-shift * u, 0f) }) { drawPath(water(84f), c.acc, alpha = 0.45f, style = line) }
        }
    }
}

/** What Harbor shows while it fetches live information. It never shows a saved copy in the meantime. */
@Composable
fun HarborLoadingScreen(message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HarborLoader()
        Text(message, style = MaterialTheme.typography.bodyMedium, color = harbor.ink2, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
