package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.location.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val TRAIL_WINDOW_MS = 6L * 3_600_000L
private const val TRAIL_MAX_POINTS = 60

/**
 * The child's last known place on an OpenStreetMap background, with a faint trail of the last few hours.
 * A stale place is drawn grey so it never looks live.
 */
@Composable
fun LocationMap(fixes: List<LocationFix>, stale: Boolean, description: String, modifier: Modifier = Modifier, accent: Color = Color.Unspecified) {
    val latest = fixes.firstOrNull() ?: return
    val latestMs = locationInstantMs(latest.at)
    val trail = remember(fixes) { fixes.filter { latestMs - locationInstantMs(it.at) <= TRAIL_WINDOW_MS }.take(TRAIL_MAX_POINTS) }
    val loader = remember { OsmTileLoader() }
    val primary = if (accent == Color.Unspecified) MaterialTheme.colorScheme.primary else accent
    val ink = if (stale) MaterialTheme.colorScheme.onSurfaceVariant else primary
    val land = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(16.dp)).semantics { contentDescription = description }) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }.toDouble()
        val height = with(density) { maxHeight.toPx() }.toDouble()
        val points = trail.map { it.latitude to it.longitude }
        val centerLat = (points.maxOf { it.first } + points.minOf { it.first }) / 2
        val centerLon = MapMath.centerLongitude(points)
        val fitted = remember(trail, width, height) { MapMath.fitZoom(points, width, height) }
        var zoom by remember(fitted) { mutableIntStateOf(fitted) }
        // Dragging moves the map; new places arriving reset it to the fitted view.
        var center by remember(trail, centerLat, centerLon) { mutableStateOf(centerLat to centerLon) }
        val moved = zoom != fitted || center != (centerLat to centerLon)
        val tiles = remember(center, zoom, width, height) { MapMath.tilesFor(center.first, center.second, zoom, width, height) }
        var loaded by remember(tiles) { mutableIntStateOf(0) }
        LaunchedEffect(tiles) {
            kotlinx.coroutines.coroutineScope {
                tiles.forEach { tile -> if (loader.cached(tile) == null) launch { if (loader.load(tile) != null) loaded++ } }
            }
        }
        var pinch by remember { mutableFloatStateOf(1f) }
        Canvas(Modifier.fillMaxSize().background(land).pointerInput(Unit) {
            detectTransformGestures { _, pan, gestureZoom, _ ->
                center = MapMath.panCenter(center.first, center.second, zoom, pan.x.toDouble(), pan.y.toDouble())
                pinch *= gestureZoom
                if (pinch >= 1.5f) { zoom = (zoom + 1).coerceAtMost(MapMath.MAX_ZOOM); pinch = 1f }
                else if (pinch <= 0.67f) { zoom = (zoom - 1).coerceAtLeast(MapMath.MIN_ZOOM); pinch = 1f }
            }
        }) {
            val redraw = loaded // Reading this state redraws the map when another tile arrives.
            if (redraw < 0) return@Canvas
            tiles.forEach { tile ->
                loader.cached(tile)?.let { image ->
                    drawImage(image, dstOffset = IntOffset(tile.left.roundToInt(), tile.top.roundToInt()), dstSize = IntSize(MapMath.TILE, MapMath.TILE))
                }
            }
            fun at(fix: LocationFix): Offset = MapMath.toViewport(fix.latitude, fix.longitude, center.first, center.second, zoom, width, height).let { Offset(it.first.toFloat(), it.second.toFloat()) }
            if (trail.size > 1) {
                val path = Path()
                trail.forEachIndexed { index, fix -> val p = at(fix); if (index == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                drawPath(path, ink.copy(alpha = 0.55f), style = Stroke(width = 3.dp.toPx()))
            }
            val here = at(latest)
            drawCircle(ink.copy(alpha = 0.16f), radius = MapMath.accuracyRadiusPx(latest.accuracyM, latest.latitude, zoom).toFloat(), center = here)
            drawCircle(Color.White, radius = 14.dp.toPx(), center = here)
            drawCircle(ink, radius = 11.dp.toPx(), center = here)
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val zoomColors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
            FilledTonalIconButton({ zoom = (zoom + 1).coerceAtMost(MapMath.MAX_ZOOM) }, Modifier.size(48.dp).semantics { contentDescription = "Zoom in" },
                enabled = zoom < MapMath.MAX_ZOOM, colors = zoomColors) { Text("+", style = MaterialTheme.typography.titleLarge) }
            FilledTonalIconButton({ zoom = (zoom - 1).coerceAtLeast(MapMath.MIN_ZOOM) }, Modifier.size(48.dp).semantics { contentDescription = "Zoom out" },
                enabled = zoom > MapMath.MIN_ZOOM, colors = zoomColors) { Text("−", style = MaterialTheme.typography.titleLarge) }
            if (moved) FilledTonalIconButton({ zoom = fitted; center = centerLat to centerLon }, Modifier.size(48.dp).semantics { contentDescription = "Reset map view" },
                colors = zoomColors) { Text("◎", style = MaterialTheme.typography.titleMedium) }
        }
        val uri = LocalUriHandler.current
        Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp).clickable { runCatching { uri.openUri(OsmTileLoader.COPYRIGHT_URL) } },
            color = Color.White.copy(alpha = 0.85f), shape = MaterialTheme.shapes.extraSmall) {
            Text(OsmTileLoader.ATTRIBUTION, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF333333))
        }
    }
}
