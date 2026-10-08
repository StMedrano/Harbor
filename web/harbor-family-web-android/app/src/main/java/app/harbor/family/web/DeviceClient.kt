package app.harbor.family.web

import android.content.Context
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** A failure with a stable code the web app understands (invalid_code, device_revoked, network, ...). */
class ApiFailure(val code: String, message: String = code) : Exception(message)

enum class UploadResult { OK, RETRY_LATER, DROP_BATCH, REVOKED }

/**
 * Native owner of the child device's identity: the Keystore signing key, the anonymous Supabase
 * session (the only place its refresh token is used, so token rotation never races with the page)
 * and every signed call to the Harbor Edge Functions.
 */
class DeviceClient(context: Context) {
    private val vault = Vault(context)
    private val random = SecureRandom()
    private val lock = Any()

    /* ── state ── */
    fun binding(): JSONObject? = vault.get(BINDING)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun isPaired(): Boolean = binding() != null
    fun lastSync(): String? = vault.get(LAST_SYNC)
    var locationEnabled: Boolean
        get() = vault.get(LOCATION_ENABLED) == "1"
        set(value) { vault.put(LOCATION_ENABLED, if (value) "1" else "0") }

    fun clear() {
        synchronized(lock) { vault.clear(); DeviceKey.delete() }
    }

    /* ── pairing ── */
    fun pair(code: String, deviceName: String, supabaseUrl: String, anonKey: String) {
        if (!Regex("^\\d{6}$").matches(code)) throw ApiFailure("invalid_code")
        val base = validatedBase(supabaseUrl)
        if (anonKey.isBlank() || anonKey.length > 600) throw ApiFailure("unknown")
        synchronized(lock) {
            if (isPaired()) throw ApiFailure("forbidden", "This phone is already paired.")
            // Reuse an anonymous identity left over from a failed attempt so retries don't create strays.
            val identity = vault.get(ANON)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optString("url") == base && it.optString("anonKey") == anonKey }
                ?: newAnonymous(base, anonKey)
            vault.put(ANON, identity.toString())
            val spki = DeviceKey.regenerate()
            val claim = JSONObject()
                .put("code", code)
                .put("publicKeySpki", spki)
                .put(
                    "device",
                    JSONObject()
                        .put("displayName", deviceName.trim().take(60).ifEmpty { "This phone" })
                        .put("model", Build.MODEL)
                        .put("androidVersion", Build.VERSION.RELEASE)
                        .put("supervisionMode", "unknown"),
                )
            val response = try {
                call(identity, ANON, "device-claim", claim.toString(), emptyMap())
            } catch (f: ApiFailure) {
                DeviceKey.delete(); throw f
            }
            if (response.code != 200) {
                DeviceKey.delete()
                throw when (response.code) {
                    400, 403, 404, 409 -> ApiFailure("invalid_code")
                    429 -> ApiFailure("too_many_attempts")
                    401 -> ApiFailure("unauthenticated")
                    else -> ApiFailure("unknown")
                }
            }
            val r = JSONObject(response.body)
            identity.put("deviceId", r.getString("deviceId"))
                .put("familyId", r.getString("familyId"))
                .put("childId", r.getString("childId"))
            vault.put(BINDING, identity.toString())
            vault.remove(ANON)
        }
    }

    private fun validatedBase(raw: String): String {
        val u = Uri.parse(raw.trim().trimEnd('/'))
        val host = u.host.orEmpty()
        if (u.scheme != "https" || !host.endsWith(".supabase.co") || u.port != -1 || u.userInfo != null || !u.path.isNullOrEmpty()) {
            throw ApiFailure("unknown", "Unsupported backend address")
        }
        return "https://$host"
    }

    private fun newAnonymous(base: String, anonKey: String): JSONObject {
        val res = try {
            Http.post("$base/auth/v1/signup", mapOf("content-type" to "application/json", "apikey" to anonKey), "{\"data\":{}}")
        } catch (_: IOException) { throw ApiFailure("network") }
        if (res.code == 429) throw ApiFailure("too_many_attempts")
        if (res.code != 200) throw ApiFailure("unknown")
        return identityFrom(JSONObject(res.body), base, anonKey)
    }

    private fun identityFrom(j: JSONObject, base: String, anonKey: String): JSONObject = JSONObject()
        .put("url", base).put("anonKey", anonKey)
        .put("access", j.getString("access_token")).put("refresh", j.getString("refresh_token"))
        .put("expiresAt", System.currentTimeMillis() + j.getLong("expires_in") * 1000)
        .put("userId", j.getJSONObject("user").getString("id"))

    /** Returns a valid access token, refreshing (and persisting the rotated refresh token) when needed. */
    private fun accessToken(identity: JSONObject, slot: String): String {
        if (identity.getLong("expiresAt") - 60_000 > System.currentTimeMillis()) return identity.getString("access")
        val base = identity.getString("url")
        val res = try {
            Http.post(
                "$base/auth/v1/token?grant_type=refresh_token",
                mapOf("content-type" to "application/json", "apikey" to identity.getString("anonKey")),
                JSONObject().put("refresh_token", identity.getString("refresh")).toString(),
            )
        } catch (_: IOException) { throw ApiFailure("network") }
        if (res.code in 400..401 || res.code == 403) throw ApiFailure("unauthenticated")
        if (res.code != 200) throw ApiFailure("network")
        val fresh = JSONObject(res.body)
        identity.put("access", fresh.getString("access_token")).put("refresh", fresh.getString("refresh_token"))
            .put("expiresAt", System.currentTimeMillis() + fresh.getLong("expires_in") * 1000)
        vault.put(slot, identity.toString())
        return identity.getString("access")
    }

    private fun call(identity: JSONObject, slot: String, function: String, body: String, extra: Map<String, String>): HttpResult {
        val token = accessToken(identity, slot)
        val headers = mutableMapOf(
            "content-type" to "application/json",
            "apikey" to identity.getString("anonKey"),
            "authorization" to "Bearer $token",
        )
        headers.putAll(extra)
        return try {
            Http.post("${identity.getString("url")}/functions/v1/$function", headers, body)
        } catch (_: IOException) { throw ApiFailure("network") }
    }

    /* ── signed calls ── */
    private fun signed(operation: String, body: String): HttpResult = synchronized(lock) {
        val identity = binding() ?: throw ApiFailure("not_found", "This phone isn't paired.")
        val deviceId = identity.getString("deviceId")
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val bodyHash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val canonical = listOf("POST", operation, deviceId, bodyHash, ts, nonce).joinToString("\n")
        call(
            identity, BINDING, operation, body,
            mapOf(
                "X-Harbor-Device-Id" to deviceId,
                "X-Harbor-Timestamp" to ts,
                "X-Harbor-Nonce" to nonce,
                "X-Harbor-Signature" to DeviceKey.sign(canonical),
            ),
        )
    }

    private fun failureFor(res: HttpResult): ApiFailure {
        val code = runCatching { JSONObject(res.body).optString("code") }.getOrDefault("")
        return when {
            code == "DEVICE_REVOKED" -> ApiFailure("device_revoked")
            res.code == 401 || code == "AUTH_REQUIRED" -> ApiFailure("unauthenticated")
            res.code == 403 -> ApiFailure("forbidden")
            res.code == 429 -> ApiFailure("network")
            else -> ApiFailure("unknown")
        }
    }

    /** device-sync. Returns the server JSON, or null when offline (the caller shows the last known state). */
    fun sync(acknowledgedVersion: Long? = null): JSONObject? {
        val body = if (acknowledgedVersion != null && acknowledgedVersion >= 0) {
            JSONObject().put("acknowledgedDesiredStateVersion", acknowledgedVersion).toString()
        } else "{}"
        val res = try { signed("device-sync", body) } catch (f: ApiFailure) {
            if (f.code == "network") return null
            throw f
        }
        if (res.code != 200) throw failureFor(res)
        vault.put(LAST_SYNC, java.time.Instant.now().toString())
        return JSONObject(res.body)
    }

    /** Uploads one batch (at most 50 points) to report-location. */
    fun reportLocations(points: JSONArray): UploadResult {
        val res = try {
            signed("report-location", JSONObject().put("points", points).toString())
        } catch (_: ApiFailure) {
            return UploadResult.RETRY_LATER // offline, or the session needs a refresh: try again later
        }
        if (res.code == 200) return UploadResult.OK
        val failure = failureFor(res)
        return when {
            failure.code == "device_revoked" -> UploadResult.REVOKED
            res.code == 400 -> UploadResult.DROP_BATCH // the server will never accept this batch
            else -> UploadResult.RETRY_LATER
        }
    }

    private companion object {
        const val BINDING = "binding"
        const val ANON = "anon"
        const val LAST_SYNC = "last_sync"
        const val LOCATION_ENABLED = "location_enabled"
    }
}
