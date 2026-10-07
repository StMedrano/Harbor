package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.mfa.FactorType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class SupabaseAuthGatewayTest {
    private val uid = "00000000-0000-4000-8000-000000000001"
    private val sid = "00000000-0000-4000-8000-000000000002"
    private fun user(email: String = "parent@example.invalid", confirmed: Boolean = true, anonymous: Boolean = false) =
        """{"id":"$uid","aud":"authenticated","email":"$email","is_anonymous":$anonymous${if (confirmed) ",\"email_confirmed_at\":\"2026-01-01T00:00:00Z\"" else ""}}"""
    private fun token(subject: String = uid): String {
        fun b64(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        return b64("""{"alg":"HS256","typ":"JWT"}""") + "." +
            b64("""{"sub":"$subject","session_id":"$sid","exp":4102444800,"iss":"https://parent.test/auth/v1","aud":"authenticated","role":"authenticated","is_anonymous":false}""") + ".c2lnbmF0dXJl"
    }
    private fun response() = """{"access_token":"${token()}","refresh_token":"new-refresh","expires_in":3600,"token_type":"bearer","user":${user()}}"""
    private fun store() = SecureAuthStore(object : AuthValues {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if(value == null) values.remove(key) else values[key] = value }
        override fun clear() = values.clear()
    }, object : AuthCipher {
        override fun encrypt(slot: String, value: ByteArray) = value
        override fun decrypt(slot: String, value: ByteArray) = value
    })
    private fun body(request: HttpRequestData) = Json.parseToJsonElement((request.body as TextContent).text).jsonObject

    @Test fun realSdkUsesPkceChallengePersistedVerifierAndExactExchange() = runTest {
        val store = store()
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(when (request.url.encodedPath) {
                "/auth/v1/signup" -> user(confirmed = false)
                "/auth/v1/recover" -> "{}"
                "/auth/v1/logout" -> "{}"
                "/auth/v1/token" -> response()
                "/auth/v1/user" -> user()
                else -> error("Unexpected SDK route ${request.url.encodedPath}")
            }, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
        try {
            val gateway = SupabaseAuthGateway(client, store)
            assertEquals(uid, gateway.signUp("parent@example.invalid", "memory-only"))
            val signup = requests.single()
            assertEquals("harbor-parent://auth/callback", signup.url.parameters["redirect_to"])
            assertEquals("s256", body(signup)["code_challenge_method"]?.jsonPrimitive?.content)
            requests.clear()
            gateway.requestRecovery("parent@example.invalid")
            val verifier = EncryptedCodeVerifierCache(store).loadCodeVerifier()!!
            val challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
            assertEquals(challenge, body(requests.single())["code_challenge"]?.jsonPrimitive?.content)
            assertEquals("harbor-parent://auth/callback", requests.single().url.parameters["redirect_to"])
            requests.clear()
            val session = gateway.exchangeCode("one-time")
            assertEquals("pkce", requests.single().url.parameters["grant_type"])
            assertEquals("one-time", body(requests.single())["auth_code"]?.jsonPrimitive?.content)
            assertEquals(verifier, body(requests.single())["code_verifier"]?.jsonPrimitive?.content)
            assertNull(EncryptedCodeVerifierCache(store).loadCodeVerifier())
            assertEquals(ParentIdentity(uid, sid), gateway.fetchVerifiedIdentity(session, "parent@example.invalid", uid))
            gateway.changePassword("changed-memory-only")
            assertEquals("changed-memory-only", body(requests.last())["password"]?.jsonPrimitive?.content)
            gateway.signOutCurrent()
            assertEquals("local", requests.last().url.parameters["scope"])
            assertNull(EncryptedSessionManager(store).loadSessionOrNull())
        } finally { client.close() }
    }

    @Test fun verifiedClaimsMustMatchConfirmedNonanonymousServerIdentity() = runTest {
        for ((email, confirmed, anonymous) in listOf(Triple("wrong@example.invalid", true, false), Triple("parent@example.invalid", false, false), Triple("parent@example.invalid", true, true))) {
            val store = store()
            val engine = MockEngine { respond(user(email, confirmed, anonymous), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
            val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
            try {
                val session = Json { ignoreUnknownKeys = true }.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(response())
                var rejected = false
                try { SupabaseAuthGateway(client, store).fetchVerifiedIdentity(session, "parent@example.invalid", uid) }
                catch (_: AuthSessionRejected) { rejected = true }
                assertTrue(rejected)
            } finally { client.close() }
        }
    }

    @Test fun recreationExplicitlyImportsEncryptedSessionBeforeSdkRefresh() = runTest {
        val store = store()
        val session = Json { ignoreUnknownKeys = true }.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(response())
        EncryptedSessionManager(store).saveSession(session.copy(refreshToken = "stored-refresh"))
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request -> requests += request; respond(response(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
        try {
            val gateway = SupabaseAuthGateway(client, store)
            assertTrue(requests.isEmpty())
            assertEquals("stored-refresh", gateway.restoreStoredSession()?.refreshToken)
            assertTrue(requests.isEmpty())
            assertEquals("new-refresh", gateway.refresh().refreshToken)
            assertEquals("refresh_token", requests.single().url.parameters["grant_type"])
            assertEquals("stored-refresh", body(requests.single())["refresh_token"]?.jsonPrimitive?.content)
        } finally { client.close() }
    }

    @Test fun rejectedServerSessionCannotSurviveRestore() = runTest {
        val store = store()
        val session = Json { ignoreUnknownKeys = true }.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(response())
        EncryptedSessionManager(store).saveSession(session)
        val engine = MockEngine { respond("""{"code":"bad_jwt","message":"Rejected"}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
        try {
            val repository = ParentAuthRepository(SupabaseAuthGateway(client, store), store)
            repository.restore()
            assertNull(repository.identity.value)
            assertNull(EncryptedSessionManager(store).loadSessionOrNull())
        } finally { client.close() }
    }

    @Test fun serverSubjectMismatchCannotUseVerifiedClaimsFromAnotherIdentity() = runTest {
        val store = store()
        val engine = MockEngine { respond(user(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
        try {
            val session = Json { ignoreUnknownKeys = true }.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(response()).copy(accessToken = token("00000000-0000-4000-8000-000000000003"))
            var rejected = false
            try { SupabaseAuthGateway(client, store).fetchVerifiedIdentity(session, "parent@example.invalid", uid) }
            catch (_: AuthSessionRejected) { rejected = true }
            assertTrue(rejected)
        } finally { client.close() }
    }

    // Approved version capability proof: product MFA remains the Task 8 seam.
    @Test fun pinnedSdkMfaChallengeImportsIntoOurEncryptedSessionManager() = runTest {
        val store = store()
        val requests = mutableListOf<HttpRequestData>()
        val factor = "00000000-0000-4000-8000-000000000004"
        val challenge = "00000000-0000-4000-8000-000000000005"
        val engine = MockEngine { request ->
            requests += request
            respond(when (request.url.encodedPath) {
                "/auth/v1/factors/" -> """{"id":"$factor","totp":{"secret":"SYNTHETIC","qr_code":"synthetic","uri":"otpauth://totp/synthetic"}}"""
                "/auth/v1/factors/$factor/challenge" -> """{"id":"$challenge","type":"totp","expires_at":4102444800}"""
                "/auth/v1/factors/$factor/verify" -> response()
                else -> error("Unexpected MFA route")
            }, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store, engine)
        try {
            val existing = Json { ignoreUnknownKeys = true }.decodeFromString<io.github.jan.supabase.auth.user.UserSession>(response())
            EncryptedSessionManager(store).saveSession(existing)
            SupabaseAuthGateway(client, store).restoreStoredSession()
            val enrolled = client.auth.mfa.enroll(FactorType.TOTP, friendlyName = "Harbor")
            assertEquals(factor, enrolled.id)
            assertEquals("totp", body(requests.single())["factor_type"]?.jsonPrimitive?.content)
            val created = client.auth.mfa.createChallenge(factor)
            assertEquals(challenge, created.id)
            val verified = client.auth.mfa.verifyChallenge(factor, challenge, "123456")
            assertEquals(challenge, body(requests.last())["challenge_id"]?.jsonPrimitive?.content)
            assertEquals("123456", body(requests.last())["code"]?.jsonPrimitive?.content)
            assertEquals(verified, EncryptedSessionManager(store).loadSession())
        } finally { client.close() }
    }
}
