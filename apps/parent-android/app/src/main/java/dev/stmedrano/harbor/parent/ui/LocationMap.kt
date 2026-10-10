package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.location.*
import kotlin.math.roundToInt

private const val TRAIL_WINDOW_MS = 6L * 3_600_000L
private const val TRAIL_MAX_POINTS = 60

/**
 * The child's last known place on an OpenStreetMap background, with a faint trail of the last few hours.
 * A stale place is drawn grey so it never looks live.
 */
@Composable
fun LocationMap(fixes: List<LocationFix>, stale: Boolean, description: String, modifier: Modifier = Modifier) {
    val latest = fixes.firstOrNull() ?: return
    val latestMs = locationInstantMs(latest.at)
    val trail = remember(fixes) { fixes.filter { latestMs - locationInstantMs(it.at) <= TRAIL_WINDOW_MS }.take(TRAIL_MAX_POINTS) }
    val loader = remember { OsmTileLoader() }
    val primary = MaterialTheme.colorScheme.primary
    val ink = if (stale) MaterialTheme.colorScheme.outline else primary
    val land = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(modifier.fillMaxWidth().height(260.dp).semantics { contentDescription = description }) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }.toDouble()
        val height = with(density) { maxHeight.toPx() }.toDouble()
        val points = trail.map { it.latitude to it.longitude }
        val centerLat = (points.maxOf { it.first } + points.minOf { it.first }) / 2
        val centerLon = (points.maxOf { it.second } + points.minOf { it.second }) / 2
        val fitted = remember(trail, width, height) { MapMath.fitZoom(points, width, height) }
        var zoom by remember(fitted) { mutableIntStateOf(fitted) }
        val tiles = remember(centerLat, centerLon, zoom, width, height) { MapMath.tilesFor(centerLat, centerLon, zoom, width, height) }
        var loaded by remember(tiles) { mutableIntStateOf(0) }
        LaunchedEffect(tiles) {
            kotlinx.coroutines.coroutineScope {
                tiles.forEach { tile -> if (loader.cached(tile) == null) launch { if (loader.load(tile) != null) loaded++ } }
            }
        }
        Canvas(Modifier.fillMaxSize().background(land)) {
            val redraw = loaded // Reading this state redraws the map when another tile arrives.
            if (redraw < 0) return@Canvas
            tiles.forEach { tile ->
                loader.cached(tile)?.let { image ->
                    drawImage(image, dstOffset = IntOffset(tile.left.roundToInt(), tile.top.roundToInt()), dstSize = IntSize(MapMath.TILE, MapMath.TILE))
                }
            }
            fun at(fix: LocationFix): Offset = MapMath.toViewport(fix.latitude, fix.longitude, centerLat, centerLon, zoom, width, height).let { Offset(it.first.toFloat(), it.second.toFloat()) }
            if (trail.size > 1) {
                val path = Path()
                trail.forEachIndexed { index, fix -> val p = at(fix); if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                drawPath(path, ink.copy(alpha = 0.55f), style = Stroke(width = 3.dp.toPx()))
            }
            val here = at(latest)
            drawCircle(ink.copy(alpha = 0.16f), radius = MapMath.accuracyRadiusPx(latest.accuracyM, latest.latitude, zoom).toFloat(), center = here)
            drawCircle(Color.White, radius = 9.dp.toPx(), center = here)
            drawCircle(ink, radius = 6.dp.toPx(), center = here)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalIconButton({ zoom = (zoom + 1).coerceAtMost(MapMath.MAX_ZOOM) }, enabled = zoom < MapMath.MAX_ZOOM) { Text("+") }
            FilledTonalIconButton({ zoom = (zoom - 1).coerceAtLeast(MapMath.MIN_ZOOM) }, enabled = zoom > MapMath.MIN_ZOOM) { Text("−") }
        }
        val uri = LocalUriHandler.current
        Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp).clickable { runCatching { uri.openUri(OsmTileLoader.COPYRIGHT_URL) } },
            color = Color.White.copy(alpha = 0.85f), shape = MaterialTheme.shapes.extraSmall) {
            Text(OsmTileLoader.ATTRIBUTION, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF333333))
        }
    }
}
