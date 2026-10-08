package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.*
import io.github.jan.supabase.auth.auth
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ParentFcmApiTest {
    private val user = "00000000-0000-4000-8000-000000000001"
    private val sessionId = "00000000-0000-4000-8000-000000000002"
    private fun accessToken(): String {
        fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return b64("""{"alg":"HS256","typ":"JWT"}""") + "." +
            b64("""{"sub":"$user","session_id":"$sessionId","exp":4102444800,"is_anonymous":false}""") + ".c2lnbmF0dXJl"
    }
    private fun store() = SecureAuthStore(object : AuthValues {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun clear() = values.clear()
    }, object : AuthCipher {
        override fun encrypt(slot: String, value: ByteArray) = value
        override fun decrypt(slot: String, value: ByteArray) = value
    })

    @Test fun SupportedSdkUsesVerifiedCaptureAndExactBodiesEvenIfSdkSessionChanges() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val userJson = """{"id":"$user","aud":"authenticated","email":"parent@example.invalid","is_anonymous":false,"email_confirmed_at":"2026-01-01T00:00:00Z"}"""
        val engine = MockEngine { request ->
            requests += request
            val body = when (request.url.encodedPath) {
                "/auth/v1/token" -> """{"access_token":"${accessToken()}","refresh_token":"synthetic","expires_in":3600,"token_type":"bearer","user":$userJson}"""
                "/auth/v1/user" -> userJson
                "/functions/v1/register-parent-fcm" -> """{"registrationId":"$REGISTRATION","active":true}"""
                "/functions/v1/remove-parent-fcm" -> "{}"
                else -> error("Unexpected parent FCM SDK route")
            }
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val store = store()
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
        try {
            val auth = ParentAuthRepository(SupabaseAuthGateway(client, store), store)
            auth.signIn("parent@example.invalid", "synthetic")
            val captured = auth.withAccessToken { it }
            client.auth.importSession(checkNotNull(client.auth.currentSessionOrNull()).copy(accessToken = "different-sdk-session"), autoRefresh = false)
            requests.clear()
            val api = SdkParentFcmApi(client, auth)
            assertEquals(REGISTRATION, api.register("installation", "synthetic-fcm-token").registrationId)
            api.remove("installation", captured)
            assertEquals(2, requests.size)
            for (request in requests) {
                assertEquals(listOf("Bearer $captured"), request.headers.getAll(HttpHeaders.Authorization))
                val content = request.body as TextContent
                assertTrue(content.contentType?.match(ContentType.Application.Json) == true)
                val body = Json.parseToJsonElement(content.text).jsonObject
                assertEquals(if (request.url.encodedPath.endsWith("register-parent-fcm")) setOf("clientInstallationId", "token") else setOf("clientInstallationId"), body.keys)
            }
            assertEquals("different-sdk-session", client.auth.currentAccessTokenOrNull())
            auth.clearLocal()
            assertTrue(runCatching { api.register("installation", "synthetic-fcm-token") }.isFailure)
            assertEquals(2, requests.size)
        } finally { client.close() }
    }
}
