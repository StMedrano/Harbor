package dev.stmedrano.harbor.parent.location

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator maths for 256-pixel OpenStreetMap tiles. Pure so it can be tested on the JVM. */
object MapMath {
    const val TILE = 256
    const val MIN_ZOOM = 3
    const val MAX_ZOOM = 18
    private const val MAX_LAT = 85.0511287798
    private const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

    fun worldSize(zoom: Int): Double = TILE * (1L shl zoom).toDouble()
    fun worldX(lon: Double, zoom: Int): Double = (lon + 180.0) / 360.0 * worldSize(zoom)
    fun worldY(lat: Double, zoom: Int): Double {
        val clamped = max(-MAX_LAT, min(MAX_LAT, lat)) * PI / 180.0
        return (1.0 - ln(tan(clamped) + 1.0 / cos(clamped)) / PI) / 2.0 * worldSize(zoom)
    }
    fun lonAt(worldX: Double, zoom: Int): Double = worldX / worldSize(zoom) * 360.0 - 180.0
    fun latAt(worldY: Double, zoom: Int): Double = atan(sinh(PI * (1.0 - 2.0 * worldY / worldSize(zoom)))) * 180.0 / PI
    fun metersPerPixel(lat: Double, zoom: Int): Double = cos(lat * PI / 180.0) * EARTH_CIRCUMFERENCE_M / worldSize(zoom)

    data class Tile(val zoom: Int, val x: Int, val y: Int, val left: Double, val top: Double)

    /** Tiles needed to fill a viewport of [width] x [height] pixels centred on [centerLat]/[centerLon]; `left`/`top` are viewport pixels. */
    fun tilesFor(centerLat: Double, centerLon: Double, zoom: Int, width: Double, height: Double): List<Tile> {
        require(zoom in MIN_ZOOM..MAX_ZOOM && width > 0 && height > 0)
        val cx = worldX(centerLon, zoom); val cy = worldY(centerLat, zoom)
        val originX = cx - width / 2; val originY = cy - height / 2
        val count = 1 shl zoom
        val firstX = floor(originX / TILE).toInt(); val lastX = floor((originX + width) / TILE).toInt()
        val firstY = max(0, floor(originY / TILE).toInt()); val lastY = min(count - 1, floor((originY + height) / TILE).toInt())
        val tiles = ArrayList<Tile>()
        for (ty in firstY..lastY) for (tx in firstX..lastX) {
            tiles += Tile(zoom, Math.floorMod(tx, count), ty, tx * TILE - originX, ty * TILE - originY)
        }
        return tiles
    }

    /** Viewport pixel of a coordinate, relative to the same centre used for [tilesFor]. */
    fun toViewport(lat: Double, lon: Double, centerLat: Double, centerLon: Double, zoom: Int, width: Double, height: Double): Pair<Double, Double> =
        (worldX(lon, zoom) - worldX(centerLon, zoom) + width / 2) to (worldY(lat, zoom) - worldY(centerLat, zoom) + height / 2)

    /** The largest zoom at which all points (plus [paddingPx]) fit the viewport; a single point uses [singlePointZoom]. */
    fun fitZoom(points: List<Pair<Double, Double>>, width: Double, height: Double, paddingPx: Double = 48.0, singlePointZoom: Int = 16): Int {
        if (points.isEmpty()) return MIN_ZOOM
        if (points.size == 1) return singlePointZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        for (zoom in MAX_ZOOM downTo MIN_ZOOM) {
            val xs = points.map { worldX(it.second, zoom) }; val ys = points.map { worldY(it.first, zoom) }
            if (xs.max() - xs.min() <= width - 2 * paddingPx && ys.max() - ys.min() <= height - 2 * paddingPx) return min(zoom, singlePointZoom + 1)
        }
        return MIN_ZOOM
    }

    fun tileUrl(tile: Tile): String = "https://tile.openstreetmap.org/${tile.zoom}/${tile.x}/${tile.y}.png"
    fun accuracyRadiusPx(accuracyM: Int, lat: Double, zoom: Int): Double = max(6.0, abs(accuracyM) / metersPerPixel(lat, zoom))
}
