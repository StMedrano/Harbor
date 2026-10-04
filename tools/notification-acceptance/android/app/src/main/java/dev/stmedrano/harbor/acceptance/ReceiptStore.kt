package dev.stmedrano.harbor.acceptance

import org.json.JSONObject

class ReceiptStore(private val save: (String) -> Unit, private val load: () -> List<String>) {
    private val keys = setOf("version", "kind", "familyId", "childId", "deviceId", "resourceId")
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private fun route(value: JSONObject): Boolean {
        if (value.opt("version") !is Number || value.opt("version").toString() != "1") return false
        val kind = value.opt("kind") as? String ?: return false
        if (kind.isBlank() || kind.length > 128) return false
        return value.keys().asSequence().all { key ->
            keys.contains(key) && (key == "version" || key == "kind" || (value.opt(key) is String && uuid.matches(value.getString(key))))
        }
    }
    @Synchronized fun record(routeJson: String, receivedAt: Long): Boolean {
        val value = try { JSONObject(routeJson) } catch (_: Exception) { return false }
        if (!route(value) || receivedAt < 0L) return false
        save(JSONObject().put("route", value).put("receivedAt", receivedAt).toString())
        return true
    }
    @Synchronized fun receipts(): List<String> = load().filter { text ->
        try {
            val value = JSONObject(text)
            value.keys().asSequence().toSet() == setOf("route", "receivedAt") &&
                value.opt("receivedAt") is Number && value.getLong("receivedAt") >= 0L && route(value.getJSONObject("route"))
        } catch (_: Exception) { false }
    }
}
