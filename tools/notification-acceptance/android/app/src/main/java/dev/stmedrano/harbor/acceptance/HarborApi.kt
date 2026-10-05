package dev.stmedrano.harbor.acceptance

import org.json.JSONObject
import java.util.Base64

data class HarborRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray)
data class HarborReply(val status: Int, val body: String)
class HarborFailure(val status: Int, val code: String) : IllegalStateException("HTTP $status $code")

class HarborApi(
    private val url: String,
    private val publishableKey: String,
    private val identity: DeviceIdentity,
    private val transport: (HarborRequest) -> HarborReply,
    private val clock: () -> Long,
    private val nonce: () -> String,
    private val sign: (ByteArray) -> ByteArray
) {
    private fun failure(reply: HarborReply): Nothing {
        val code = try { JSONObject(reply.body).optString("code") } catch (_: Exception) { "" }
        throw HarborFailure(reply.status, if (reply.status == 403 && code == "DEVICE_REVOKED") "DEVICE_REVOKED" else "REQUEST_FAILED")
    }
    init {
        require(url == "https://bfvybxkjxilntjgndsrm.supabase.co") { "Only the approved development backend is allowed" }
        require(publishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "Public publishable key required" }
    }
    private fun request(path: String, body: JSONObject, token: String? = null, proofBinding: DeviceBinding? = null): HarborReply {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        val headers = mutableMapOf("apikey" to publishableKey, "Content-Type" to "application/json")
        if (token != null) headers["Authorization"] = "Bearer $token"
        if (proofBinding != null) {
            require(proofBinding == identity.binding) { "Use only the claimed device binding" }
            val operation = path.substringAfterLast('/')
            val timestamp = clock()
            val freshNonce = nonce()
            val signature = sign(canonicalProof("POST", operation, proofBinding.deviceId, bytes, timestamp, freshNonce))
            require(signature.size == 64) { "P1363 device signature required" }
            headers["X-Harbor-Device-Id"] = proofBinding.deviceId
            headers["X-Harbor-Timestamp"] = timestamp.toString()
            headers["X-Harbor-Nonce"] = freshNonce
            headers["X-Harbor-Signature"] = Base64.getEncoder().encodeToString(signature)
        }
        return transport(HarborRequest(url + path, "POST", headers, bytes))
    }
    private fun session(reply: HarborReply): ChildSession {
        check(reply.status in 200..299) { "Child authentication failed; retry enrollment" }
        val value = JSONObject(reply.body)
        require(value.getJSONObject("user").optBoolean("is_anonymous", false)) { "Parent credentials are forbidden" }
        val expiry = if (value.has("expires_at")) value.getLong("expires_at") else clock() + value.getLong("expires_in")
        return ChildSession(value.getString("access_token"), value.getString("refresh_token"), expiry, true)
    }
    fun anonymousSignup(): ChildSession = session(request("/auth/v1/signup", JSONObject())).also { identity.acceptSession(it) }
    fun refreshSession(refreshToken: String): ChildSession {
        require(refreshToken.isNotBlank())
        return session(request("/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", refreshToken)))
    }
    fun claim(code: String, spkiBase64: String): DeviceBinding {
        require(code.matches(Regex("[0-9]{6}")) && spkiBase64.isNotBlank()) { "Fresh pairing code and public key required" }
        val body = JSONObject().put("code", code).put("publicKeySpki", spkiBase64)
            .put("device", JSONObject().put("displayName", "Harbor acceptance Android").put("supervisionMode", "unknown"))
        val reply = request("/functions/v1/device-claim", body, identity.accessToken())
        check(reply.status == 200) { "Device claim failed; obtain a fresh pairing code" }
        val value = JSONObject(reply.body)
        val binding = DeviceBinding(value.getString("deviceId"), value.getString("familyId"), value.getString("childId"))
        identity.acceptBinding(binding)
        return binding
    }
    fun registerFcm(binding: DeviceBinding, token: String) {
        require(token.isNotBlank()) { "FCM token required" }
        val reply = request("/functions/v1/register-fcm", JSONObject().put("token", token), identity.accessToken(), binding)
        if (reply.status != 204) failure(reply)
    }
    fun sync(binding: DeviceBinding): String {
        val reply = request("/functions/v1/device-sync", JSONObject(), identity.accessToken(), binding)
        if (reply.status != 200) failure(reply)
        return reply.body
    }
}
