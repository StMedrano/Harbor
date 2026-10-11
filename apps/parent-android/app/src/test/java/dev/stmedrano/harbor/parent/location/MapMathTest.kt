package dev.stmedrano.harbor.parent.location

import org.junit.Assert.*
import org.junit.Test

class MapMathTest {
    @Test fun knownTileContainsKnownPlace() {
        // The Statue of Liberty falls in zoom-10 tile 301/385.
        val tiles = MapMath.tilesFor(40.6892, -74.0445, 10, 256.0, 256.0)
        assertTrue(tiles.any { it.x == 301 && it.y == 385 })
    }
    @Test fun worldRoundTrips() {
        val lat = 41.878114; val lon = -87.629798
        assertEquals(lat, MapMath.latAt(MapMath.worldY(lat, 14), 14), 1e-9)
        assertEquals(lon, MapMath.lonAt(MapMath.worldX(lon, 14), 14), 1e-9)
    }
    @Test fun centerIsAtViewportMiddleAndTilesCoverViewport() {
        val (x, y) = MapMath.toViewport(41.0, -87.0, 41.0, -87.0, 15, 400.0, 300.0)
        assertEquals(200.0, x, 1e-6); assertEquals(150.0, y, 1e-6)
        val tiles = MapMath.tilesFor(41.0, -87.0, 15, 400.0, 300.0)
        assertTrue(tiles.minOf { it.left } <= 0.0 && tiles.maxOf { it.left } + MapMath.TILE >= 400.0)
        assertTrue(tiles.minOf { it.top } <= 0.0 && tiles.maxOf { it.top } + MapMath.TILE >= 300.0)
    }
    @Test fun tilesWrapAroundTheDateLineAndStayInRange() {
        val tiles = MapMath.tilesFor(0.0, 179.99, 3, 800.0, 400.0)
        assertTrue(tiles.all { it.x in 0..7 && it.y in 0..7 })
        assertTrue(tiles.any { it.x == 0 } && tiles.any { it.x == 7 })
    }
    @Test fun metersPerPixelHalvesWithEachZoom() {
        assertEquals(MapMath.metersPerPixel(40.0, 12) / 2, MapMath.metersPerPixel(40.0, 13), 1e-9)
        assertEquals(156543.03, MapMath.metersPerPixel(0.0, 0), 0.01)
    }
    @Test fun fitZoomShrinksForSpreadPoints() {
        val near = MapMath.fitZoom(listOf(41.0 to -87.0, 41.0005 to -87.0005), 400.0, 300.0)
        val far = MapMath.fitZoom(listOf(41.0 to -87.0, 42.0 to -88.0), 400.0, 300.0)
        assertTrue(near > far)
        assertEquals(16, MapMath.fitZoom(listOf(41.0 to -87.0), 400.0, 300.0))
        assertEquals(MapMath.MIN_ZOOM, MapMath.fitZoom(emptyList(), 400.0, 300.0))
    }
    @Test fun dateLineHistoryFitsNearbyLocationsRatherThanTheWholePlanet() {
        val points = listOf(0.0 to 179.95, 0.0 to -179.95)
        val zoom = MapMath.fitZoom(points, 400.0, 300.0)
        assertTrue("Near-date-line fixes should remain zoomed into the local area", zoom >= 9)
        val center = MapMath.centerLongitude(points)
        assertTrue("Centre must stay at the date line, not Greenwich", kotlin.math.abs(center) >= 179.9)
    }

    @Test fun dateLineHistoryProjectsOntoTheSameMapViewport() {
        val center = MapMath.centerLongitude(listOf(0.0 to 179.99, 0.0 to -179.99))
        val east = MapMath.toViewport(0.0, 179.99, 0.0, center, 12, 400.0, 300.0)
        val west = MapMath.toViewport(0.0, -179.99, 0.0, center, 12, 400.0, 300.0)
        assertTrue(east.first in 150.0..250.0)
        assertTrue(west.first in 150.0..250.0)
        assertTrue(kotlin.math.abs(east.first - west.first) < 100.0)
    }

    @Test fun urlsUseTheStandardTileHost() {
        assertEquals("https://tile.openstreetmap.org/10/301/385.png", MapMath.tileUrl(MapMath.Tile(10, 301, 385, 0.0, 0.0)))
    }
}
