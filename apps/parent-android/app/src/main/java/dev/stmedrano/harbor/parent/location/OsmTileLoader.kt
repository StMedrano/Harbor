package dev.stmedrano.harbor.parent.location

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches OpenStreetMap standard tiles for the parent's map. Development use only: the public tile servers are not for
 * production traffic, and a hosted tile provider replaces this before any wider release. Tiles are cached in memory only,
 * requests identify the app as OSM's tile policy requires, and nothing about the child leaves the phone with a request except
 * the tile numbers for the area being viewed.
 */
class OsmTileLoader {
    private val cache = object : LruCache<String, ImageBitmap>(96) {}
    private val gate = Semaphore(4)
    private val failed = HashMap<String, Long>()

    fun cached(tile: MapMath.Tile): ImageBitmap? = cache.get(key(tile))
    private fun key(tile: MapMath.Tile) = "${tile.zoom}/${tile.x}/${tile.y}"

    /** Returns null when the tile cannot be loaded now; the map then shows a plain background for it. */
    suspend fun load(tile: MapMath.Tile): ImageBitmap? {
        cached(tile)?.let { return it }
        val id = key(tile)
        synchronized(failed) { failed[id]?.takeIf { System.currentTimeMillis() - it < 30_000 }?.let { return null } }
        return gate.withPermit {
            cached(tile)?.let { return@withPermit it }
            withContext(Dispatchers.IO) {
                try {
                    val connection = URL(MapMath.tileUrl(tile)).openConnection() as HttpURLConnection
                    try {
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = 8000; connection.readTimeout = 8000
                        connection.setRequestProperty("User-Agent", USER_AGENT)
                        if (connection.responseCode != 200 || connection.contentType?.startsWith("image/") != true) throw java.io.IOException("Tile unavailable")
                        val bytes = connection.inputStream.use { input ->
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                            while (true) { val n = input.read(buffer); if (n < 0) break; out += buffer.copyOf(n); if (out.size() > MAX_TILE_BYTES) throw java.io.IOException("Tile too large") }
                            out.toByteArray()
                        }
                        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw java.io.IOException("Tile not an image")
                        bitmap.asImageBitmap().also { cache.put(id, it) }
                    } finally { connection.disconnect() }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { synchronized(failed) { failed[id] = System.currentTimeMillis() }; null }
            }
        }
    }
    private operator fun java.io.ByteArrayOutputStream.plusAssign(bytes: ByteArray) = write(bytes, 0, bytes.size)
    companion object {
        private const val MAX_TILE_BYTES = 262_144
        const val USER_AGENT = "HarborFamilyDevelopment/0.15 (Android; https://github.com/StMedrano/Harbor)"
        const val ATTRIBUTION = "© OpenStreetMap contributors"
        const val COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"
    }
}
