package dev.stmedrano.harbor.parent.child

import dev.stmedrano.harbor.parent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.UUID

data class ChildRequest(val url: String, val method: String, val headers: Map<String, String>, val body: ByteArray) {
    override fun toString() = "ChildRequest(redacted)"
}
data class ChildReply(val status: Int, val body: String) {
    override fun toString() = "ChildReply(status=$status)"
}
class ChildApi(private val url: String, private val publishableKey: String,
    private val transport: suspend (ChildRequest) -> ChildReply, private val clock: () -> Long,
    private val nonce: () -> String, private val key: ChildSigner) : ChildBackend {
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    init {
        require(url == BuildConfig.SUPABASE_URL) { "Unassigned child environment" }
        require(publishableKey.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "Public publishable key required" }
    }
    private fun validate(session: ChildAuthSession) {
        check(session.anonymous && uuid.matches(session.userId) && session.credentials.accessToken.isNotBlank() &&
            session.credentials.refreshToken.isNotBlank()) { "Anonymous child identity required" }
    }
    private fun failure(reply: ChildReply): Nothing {
        if (reply.status >= 500 || reply.status == 429) throw ChildConnectionUnavailable()
        val code = try { Json.parseToJsonElement(reply.body).jsonObject["code"]?.jsonPrimitive?.content }
        catch (_: Exception) { null }
        throw ChildRequestDenied(reply.status, if (reply.status == 403 && code == "DEVICE_REVOKED") "DEVICE_REVOKED" else "REQUEST_FAILED")
    }
    private fun objectReply(reply: ChildReply): JsonObject {
        if (reply.status !in 200..299) failure(reply)
        return try { Json.parseToJsonElement(reply.body).jsonObject }
        catch (_: Exception) { throw IllegalStateException("Invalid child response") }
    }
    private fun string(value: JsonObject, field: String): String {
        val primitive = value[field] as? JsonPrimitive
        check(primitive?.isString == true && primitive.content.isNotBlank()) { "Invalid child response" }
        return primitive.content
    }
    private fun session(reply: ChildReply): ChildAuthSession {
        val body = objectReply(reply)
        return try {
            val user = body.getValue("user").jsonObject
            check(user["is_anonymous"]?.jsonPrimitive?.booleanOrNull == true)
            val expiry = body["expires_at"]?.jsonPrimitive?.longOrNull
                ?: (clock() + checkNotNull(body["expires_in"]?.jsonPrimitive?.longOrNull))
            check(expiry > clock())
            ChildAuthSession(ChildCredentials(string(body, "access_token"), string(body, "refresh_token"), expiry),
                string(user, "id"), true).also(::validate)
        } catch (_: Exception) { throw IllegalStateException("Invalid anonymous child session") }
    }
    private suspend fun request(path: String, body: JsonObject, session: ChildAuthSession? = null, binding: ChildBinding? = null): ChildReply {
        return requestBytes(path, body.toString().toByteArray(Charsets.UTF_8), session, binding)
    }
    private suspend fun requestBytes(path:String,bytes:ByteArray,session:ChildAuthSession?=null,binding:ChildBinding?=null):ChildReply {
        val headers = mutableMapOf("apikey" to publishableKey, "Content-Type" to "application/json")
        session?.let { validate(it); check(it.credentials.expiresAt > clock()); headers["Authorization"] = "Bearer ${it.credentials.accessToken}" }
        if (binding != null) {
            check(session != null && key.exists()) { "Claimed child key required" }
            require(listOf(binding.deviceId, binding.familyId, binding.childId).all(uuid::matches))
            val timestamp = clock(); val freshNonce = nonce()
            val signature = key.sign(canonicalChildProof("POST", path.substringAfterLast('/'), binding.deviceId, bytes, timestamp, freshNonce))
            require(signature.size == 64) { "P1363 child proof required" }
            headers["X-Harbor-Device-Id"] = binding.deviceId
            headers["X-Harbor-Timestamp"] = timestamp.toString()
            headers["X-Harbor-Nonce"] = freshNonce
            headers["X-Harbor-Signature"] = Base64.getEncoder().encodeToString(signature)
        }
        return transport(ChildRequest(url + path, "POST", headers, bytes))
    }
    override suspend fun anonymousSignup() = session(request("/auth/v1/signup", buildJsonObject {}))
    override suspend fun refresh(session: ChildAuthSession): ChildAuthSession {
        validate(session)
        val refreshed = session(request("/auth/v1/token?grant_type=refresh_token", buildJsonObject {
            put("refresh_token", session.credentials.refreshToken)
        }))
        check(refreshed.userId == session.userId) { "Child owner changed" }
        return refreshed
    }
    override suspend fun claim(code: String, publicKeySpki: String, session: ChildAuthSession): ChildBinding {
        require(code.matches(Regex("[0-9]{6}")) && publicKeySpki.isNotBlank())
        val reply = request("/functions/v1/device-claim", buildJsonObject {
            put("code", code); put("publicKeySpki", publicKeySpki)
            put("device", buildJsonObject { put("displayName", "Harbor Family"); put("supervisionMode", "unknown") })
        }, session)
        if (reply.status != 200) failure(reply)
        val value = objectReply(reply)
        return ChildBinding(string(value, "deviceId"), string(value, "familyId"), string(value, "childId")).also {
            check(listOf(it.deviceId, it.familyId, it.childId).all(uuid::matches)) { "Invalid claim binding" }
        }
    }
    override suspend fun sync(binding: ChildBinding, session: ChildAuthSession): Long {
        val reply = request("/functions/v1/device-sync", buildJsonObject {}, session, binding)
        if (reply.status != 200) failure(reply)
        val value = objectReply(reply)
        return checkNotNull(value["desiredStateVersion"]?.jsonPrimitive?.longOrNull).also { check(it >= 0) }
    }
    override suspend fun registerFcm(binding: ChildBinding, token: String, session: ChildAuthSession) {
        require(token.isNotBlank())
        val reply = request("/functions/v1/register-fcm", buildJsonObject { put("token", token) }, session, binding)
        if (reply.status != 204) failure(reply)
    }
    suspend fun signedUsage(operation:String,body:String,binding:ChildBinding,session:ChildAuthSession):String {
        require(operation in setOf("report-device-usage","clear-device-usage","get-device-usage-checkpoint"))
        val bytes=body.toByteArray(Charsets.UTF_8)
        require(bytes.size<=1_048_576)
        val reply=requestBytes("/functions/v1/$operation",bytes,session,binding)
        if(reply.status!=200) {
            // Only 401/403 mean the child identity is wrong; other 4xx are rejections of this one request.
            if(reply.status in 400..499&&reply.status !in setOf(401,403,429)) {
                val code=try{Json.parseToJsonElement(reply.body).jsonObject["code"]?.jsonPrimitive?.content}catch(_:Exception){null}
                throw UsageRequestRejected(reply.status,code?.takeIf{it.matches(Regex("[A-Z_]{1,40}"))}?:"REQUEST_FAILED")
            }
            failure(reply)
        }
        return reply.body
    }
    companion object {
        fun create(key: ChildSigner) = ChildApi(BuildConfig.SUPABASE_URL, BuildConfig.PUBLISHABLE_KEY, ::childTransport,
            { System.currentTimeMillis() / 1000 }, { UUID.randomUUID().toString() }, key)
    }
}

private suspend fun childTransport(request: ChildRequest): ChildReply = withContext(Dispatchers.IO) {
    check(!BuildConfig.CI_FIXTURE) { "Offline fixture cannot send child credentials" }
    val uri = URI(request.url)
    val assigned = URI(BuildConfig.SUPABASE_URL)
    require(uri.scheme == "https" && uri.host == assigned.host && uri.port == -1 && uri.userInfo == null)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    val deadline = System.nanoTime() + 20_000_000_000L
    try {
        currentCoroutineContext().ensureActive()
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 10000; connection.readTimeout = 10000
        connection.requestMethod = request.method
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.doOutput = true
        connection.outputStream.use { it.write(request.body) }
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = input?.use {
            val result = ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) {
                currentCoroutineContext().ensureActive()
                if (System.nanoTime() >= deadline) throw ChildConnectionUnavailable()
                val count = it.read(buffer)
                if (count < 0) break
                check(result.size() + count <= 1_048_576) { "Child response too large" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: byteArrayOf()
        ChildReply(status, bytes.toString(Charsets.UTF_8))
    } finally { connection.disconnect() }
}
