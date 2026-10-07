package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class ParentApiTest {
    private val user = "00000000-0000-4000-8000-000000000001"
    private val session = "00000000-0000-4000-8000-000000000002"
    private val family = "00000000-0000-4000-8000-000000000003"
    private val child = "00000000-0000-4000-8000-000000000004"
    private val date = "2026-01-01T00:00:00Z"
    private fun token(): String {
        fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return b64("""{"alg":"HS256","typ":"JWT"}""") + "." + b64("""{"sub":"$user","session_id":"$session","exp":4102444800,"is_anonymous":false}""") + ".c2lnbmF0dXJl"
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

    @Test fun realSdkUsesAuthenticatedPublicReadsAndExactMutationBodies() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val userJson = """{"id":"$user","aud":"authenticated","email":"parent@example.invalid","is_anonymous":false,"email_confirmed_at":"$date"}"""
        val childJson = """{"id":"$child","family_id":"$family","display_name":"Child","created_at":"$date","updated_at":"$date"}"""
        val engine = MockEngine { request ->
            requests += request
            respond(when (request.url.encodedPath) {
                "/auth/v1/token" -> """{"access_token":"${token()}","refresh_token":"synthetic","expires_in":3600,"token_type":"bearer","user":$userJson}"""
                "/auth/v1/user" -> userJson
                "/rest/v1/families" -> """[{"id":"$family","name":"Family","timezone":"UTC","created_at":"$date","updated_at":"$date"}]"""
                "/rest/v1/family_members" -> """[{"id":"membership","family_id":"$family","user_id":"$user","role":"owner","status":"active","created_at":"$date","updated_at":"$date"}]"""
                "/rest/v1/profiles" -> """[{"id":"$user","display_name":"Parent","created_at":"$date","updated_at":"$date"}]"""
                "/rest/v1/children" -> "[$childJson]"
                "/rest/v1/devices_public" -> "[]"
                "/functions/v1/create-family" -> """{"familyId":"$family","name":"Family","role":"owner"}"""
                "/functions/v1/create-child" -> """{"version":1,"id":"$child","familyId":"$family","displayName":"Child","createdAt":"$date","updatedAt":"$date"}"""
                "/functions/v1/create-device-pairing" -> """{"code":"123456","expiresAt":"2026-01-01T00:10:00Z"}"""
                else -> error("Unexpected parent SDK route")
            }, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val store = store()
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
        try {
            val auth = ParentAuthRepository(SupabaseAuthGateway(client, store), store)
            auth.signIn("parent@example.invalid", "synthetic")
            requests.clear()
            val api = SdkParentApi(client, auth) { 1234 }
            assertEquals(family, api.listFamilies().single().id)
            val read = api.readFamily(family)
            assertEquals(user, read.membership.userId)
            assertEquals(child, read.children.single().id)
            assertEquals(1234L, read.fetchedAt)
            assertEquals(family, api.createFamily("Family", "family-key").familyId)
            assertEquals(child, api.createChild(CreateChildRequest(family, "Child", "child-key")).id)
            assertEquals("123456", api.createPairing(child).code)
            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer ${token()}" })
            val member = requests.single { it.url.encodedPath == "/rest/v1/family_members" }
            assertEquals("eq.$user", member.url.parameters["user_id"])
            assertEquals("eq.$family", member.url.parameters["family_id"])
            for (request in requests.filter { it.url.encodedPath.startsWith("/functions/") }) {
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                assertFalse(body.containsKey("userId"))
                assertFalse(body.containsKey("sessionId"))
                val contentType = request.body.contentType ?: request.headers[HttpHeaders.ContentType]?.let(ContentType::parse)
                assertTrue("Mutation must send JSON", contentType?.match(ContentType.Application.Json) == true)
                val expected = when (request.url.encodedPath) {
                    "/functions/v1/create-family" -> setOf("name", "idempotencyKey")
                    "/functions/v1/create-child" -> setOf("familyId", "displayName", "idempotencyKey")
                    else -> setOf("childId")
                }
                assertEquals(expected, body.keys)
            }
        } finally { client.close() }
    }
}
