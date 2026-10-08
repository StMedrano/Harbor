package app.harbor.family.web

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Encrypted on-device queue of location fixes waiting to be uploaded, so nothing is lost while the
 * phone is offline. Capped, and anything older than the server's 7-day retention is discarded.
 */
class LocationQueue(private val vault: Vault) {
    @Synchronized
    fun add(point: JSONObject) {
        val all = read()
        all.add(point)
        write(prune(all))
    }

    @Synchronized
    fun peek(n: Int): JSONArray = JSONArray().also { out -> read().take(n).forEach(out::put) }

    @Synchronized
    fun drop(n: Int) { write(read().drop(n)) }

    @Synchronized
    fun size(): Int = read().size

    @Synchronized
    fun clear() { vault.remove(KEY) }

    private fun read(): MutableList<JSONObject> {
        val raw = vault.get(KEY) ?: return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { arr.getJSONObject(it) }
        }.getOrDefault(mutableListOf())
    }

    private fun write(list: List<JSONObject>) {
        if (list.isEmpty()) vault.remove(KEY) else vault.put(KEY, JSONArray(list).toString())
    }

    private fun prune(list: MutableList<JSONObject>): List<JSONObject> {
        val cutoff = Instant.now().minusSeconds(6 * 24 * 3600L)
        val fresh = list.filter { runCatching { Instant.parse(it.getString("recordedAt")).isAfter(cutoff) }.getOrDefault(false) }
        return if (fresh.size > MAX) fresh.takeLast(MAX) else fresh
    }

    private companion object {
        const val KEY = "location_queue"
        const val MAX = 1000
    }
}
