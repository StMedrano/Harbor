package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.location.MapMath
import dev.stmedrano.harbor.parent.location.OsmTileLoader
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** One person on the map. [stale] people are drawn grey so an old place never looks live. */
data class MapPerson(val id: String, val name: String, val latitude: Double, val longitude: Double, val color: Color, val stale: Boolean, val self: Boolean, val description: String)

/**
 * Several people on an OpenStreetMap background, fitted to show everyone, with zoom buttons, pinch and drag.
 * It draws only the latest place for each person: no trails and no history.
 */
@Composable
fun PeopleMap(people: List<MapPerson>, modifier: Modifier = Modifier) {
    if (people.isEmpty()) return
    val loader = remember { OsmTileLoader() }
    val c = harbor
    val land = c.land
    BoxWithConstraints(modifier.fillMaxWidth().height(280.dp).clip(RoundedCornerShape(20.dp))) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }.toDouble()
        val height = with(density) { maxHeight.toPx() }.toDouble()
        val points = people.map { it.latitude to it.longitude }
        val fittedCenter = remember(points) {
            val lat = (points.maxOf { it.first } + points.minOf { it.first }) / 2
            lat to MapMath.centerLongitude(points)
        }
        val fitted = remember(points, width, height) { MapMath.fitZoom(points, width, height) }
        var zoom by remember(fitted) { mutableIntStateOf(fitted) }
        var center by remember(points) { mutableStateOf(fittedCenter) }
        val moved = zoom != fitted || center != fittedCenter
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
        }
        people.forEach { person ->
            val (x, y) = MapMath.toViewport(person.latitude, person.longitude, center.first, center.second, zoom, width, height)
            val half = with(density) { 19.dp.toPx() }
            if (x > -half && x < width + half && y > -half && y < height + half) {
                // Design system map pins: the person's crew colour in a cream ring with deep-water initials; an old place is greyed.
                val fill = if (person.stale) c.s2 else person.color
                Box(Modifier.offset { IntOffset((x - half).roundToInt(), (y - half).roundToInt()) }.size(38.dp)
                    .then(if (person.self) Modifier.border(2.dp, c.brand, CircleShape).padding(2.dp) else Modifier)
                    .background(c.pinRing, CircleShape).padding(3.dp).background(fill, CircleShape)
                    .semantics { contentDescription = person.description }, contentAlignment = Alignment.Center) {
                    Text(person.name.take(1).uppercase(), color = if (person.stale) c.ink2 else c.onColor, style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                }
            }
        }
        Column(Modifier.align(Alignment.TopEnd).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface)
            FilledTonalIconButton({ zoom = (zoom + 1).coerceAtMost(MapMath.MAX_ZOOM) }, Modifier.size(48.dp).semantics { contentDescription = "Zoom in" },
                enabled = zoom < MapMath.MAX_ZOOM, colors = colors) { Text("+", style = MaterialTheme.typography.titleLarge) }
            FilledTonalIconButton({ zoom = (zoom - 1).coerceAtLeast(MapMath.MIN_ZOOM) }, Modifier.size(48.dp).semantics { contentDescription = "Zoom out" },
                enabled = zoom > MapMath.MIN_ZOOM, colors = colors) { Text("−", style = MaterialTheme.typography.titleLarge) }
            if (moved) FilledTonalIconButton({ zoom = fitted; center = fittedCenter }, Modifier.size(48.dp).semantics { contentDescription = "Show everyone" },
                colors = colors) { Text("◎", style = MaterialTheme.typography.titleMedium) }
        }
        val uri = LocalUriHandler.current
        Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp).clickable { runCatching { uri.openUri(OsmTileLoader.COPYRIGHT_URL) } },
            color = Color.White.copy(alpha = 0.85f), shape = MaterialTheme.shapes.extraSmall) {
            Text(OsmTileLoader.ATTRIBUTION, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFF333333))
        }
    }
}
