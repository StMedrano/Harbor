package app.harbor.family.web

import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * window.HarborDevice for the Harbor Family page. Every method takes (callbackId, argsJson), returns
 * immediately, and answers through window.harborDeviceResult(callbackId, json) with
 * { status:'ok', ... } or { status:'error', code, message }. Calls from any page other than the
 * Harbor site are refused.
 */
class DeviceBridge(
    private val activity: AppCompatActivity,
    private val web: WebView,
    private val client: DeviceClient,
    private val consent: LocationConsent,
    private val trusted: () -> Boolean,
) {
    private val io = Executors.newSingleThreadExecutor()

    @JavascriptInterface fun status(id: String, args: String) = run(id) { ok(statusJson()) }

    @JavascriptInterface fun pair(id: String, args: String) = run(id) {
        val a = JSONObject(args)
        client.pair(a.optString("code"), a.optString("deviceName", "This phone"), a.optString("supabaseUrl"), a.optString("anonKey"))
        ok(statusJson())
    }

    @JavascriptInterface fun sync(id: String, args: String) = run(id) {
        val b = client.binding() ?: throw ApiFailure("not_found")
        val ack = runCatching { JSONObject(args).getLong("ack") }.getOrNull()
        val result = client.sync(ack)
        ok(
            JSONObject()
                .put("childId", b.getString("childId"))
                .put("offline", result == null)
                .put("lastSync", client.lastSync() ?: JSONObject.NULL)
                .put("desiredStateVersion", result?.opt("desiredStateVersion") ?: JSONObject.NULL)
                .put("desiredState", result?.opt("desiredState") ?: JSONObject.NULL)
                .put("location", locationJson()),
        )
    }

    @JavascriptInterface fun enableLocation(id: String, args: String) {
        if (!allowed(id)) return
        activity.runOnUiThread {
            if (!client.isPaired()) { reply(id, fail("not_found")); return@runOnUiThread }
            consent.start { reply(id, ok(JSONObject().put("location", locationJson()))) }
        }
    }

    @JavascriptInterface fun disableLocation(id: String, args: String) {
        if (!allowed(id)) return
        activity.runOnUiThread {
            consent.disable()
            reply(id, ok(JSONObject().put("location", locationJson())))
        }
    }

    @JavascriptInterface fun clear(id: String, args: String) = run(id) {
        activity.runOnUiThread { LocationService.stop(activity) }
        LocationQueue(Vault(activity)).clear()
        client.clear()
        ok(JSONObject())
    }

    /* ── helpers ── */
    private fun statusJson(): JSONObject {
        val b = client.binding()
        val o = JSONObject().put("paired", b != null).put("location", locationJson())
        if (b != null) o.put("deviceId", b.getString("deviceId")).put("familyId", b.getString("familyId")).put("childId", b.getString("childId")).put("authUserId", b.getString("userId"))
        return o
    }

    private fun locationJson(): JSONObject = JSONObject()
        .put("available", true)
        .put("enabled", client.locationEnabled)
        .put("permission", LocationService.permissionLevel(activity))

    private fun ok(extra: JSONObject): JSONObject {
        extra.put("status", "ok"); return extra
    }

    private fun fail(code: String, message: String? = null): JSONObject =
        JSONObject().put("status", "error").put("code", code).put("message", message ?: JSONObject.NULL)

    private fun validId(id: String) = Regex("^d\\d{1,9}$").matches(id)

    private fun allowed(id: String): Boolean {
        if (!validId(id)) return false
        if (!trusted()) { reply(id, fail("forbidden")); return false }
        return true
    }

    private fun run(id: String, block: () -> JSONObject) {
        if (!allowed(id)) return
        io.execute {
            val result = try {
                block()
            } catch (f: ApiFailure) {
                fail(f.code, if (f.message == f.code) null else f.message)
            } catch (_: Exception) {
                fail("unknown")
            }
            reply(id, result)
        }
    }

    private fun reply(id: String, json: JSONObject) {
        val js = "window.harborDeviceResult&&window.harborDeviceResult(${JSONObject.quote(id)},${JSONObject.quote(json.toString())})"
        activity.runOnUiThread { web.evaluateJavascript(js, null) }
    }
}
