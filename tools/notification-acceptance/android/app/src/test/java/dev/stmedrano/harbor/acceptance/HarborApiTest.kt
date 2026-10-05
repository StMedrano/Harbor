package dev.stmedrano.harbor.acceptance

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class HarborApiTest {
    @Test fun revokedDenialIsSafeAndDistinctForBothSignedOperations() {
        val api = HarborApi("https://bfvybxkjxilntjgndsrm.supabase.co", "sb_publishable_fixture", identity(), {HarborReply(403,"{\"code\":\"DEVICE_REVOKED\",\"message\":\"private\"}")}, {100L}, {"nonce"}, {ByteArray(64)})
        for (operation in listOf<() -> Unit>({api.sync(binding); Unit}, {api.registerFcm(binding,"token")})) {
            val failure = assertThrows(HarborFailure::class.java, operation)
            assertEquals(403,failure.status);assertEquals("DEVICE_REVOKED",failure.code)
            assertFalse(failure.message.orEmpty().contains("private"))
        }
    }
    private val binding = DeviceBinding("12345678-1234-4234-8234-123456789abc", "23456789-1234-4234-8234-123456789abc", "34567890-1234-4234-8234-123456789abc")
    private fun identity(): DeviceIdentity = DeviceIdentity({100L}, {throw IllegalStateException("Unexpected refresh")}, {}).also {
        it.acceptSession(ChildSession("child-access", "child-refresh", 500L, true))
        it.acceptBinding(binding)
    }
    @Test fun signedRequestsUseFreshNoncesAndExactSerializedBody() {
        val requests = mutableListOf<HarborRequest>()
        val signed = mutableListOf<String>()
        var sequence = 0
        val api = HarborApi(
            "https://bfvybxkjxilntjgndsrm.supabase.co", "sb_publishable_fixture", identity(),
            {request -> requests.add(request); HarborReply(204, "")}, {100L}, {"nonce-${++sequence}"},
            {bytes -> signed.add(bytes.toString(Charsets.UTF_8)); ByteArray(64) { 1 }}
        )
        api.registerFcm(binding, "real-token")
        api.registerFcm(binding, "rotated-token")
        assertEquals("nonce-1", requests[0].headers["X-Harbor-Nonce"])
        assertEquals("nonce-2", requests[1].headers["X-Harbor-Nonce"])
        assertEquals("Bearer child-access", requests[0].headers["Authorization"])
        assertEquals("real-token", JSONObject(requests[0].body.toString(Charsets.UTF_8)).getString("token"))
        assertEquals("POST", requests[0].method)
        assertEquals("https://bfvybxkjxilntjgndsrm.supabase.co/functions/v1/register-fcm", requests[0].url)
        assertTrue(signed[0].startsWith("POST\nregister-fcm\n${binding.deviceId}\n"))
        assertTrue(signed[0].endsWith("\n100\nnonce-1"))
        assertNotEquals(signed[0], signed[1])
        assertEquals(88, requests[0].headers.getValue("X-Harbor-Signature").length)
    }
    @Test fun backendRejectionDoesNotConfirmRegistration() {
        val api = HarborApi("https://bfvybxkjxilntjgndsrm.supabase.co", "sb_publishable_fixture", identity(), {HarborReply(403, "private error")}, {100L}, {"nonce"}, {ByteArray(64)})
        val error = assertThrows(IllegalStateException::class.java) { api.registerFcm(binding, "token") }
        assertFalse(error.message.orEmpty().contains("private error"))
    }
    @Test fun foreignBackendAndServerKeysAreRejectedBeforeNetwork() {
        var calls = 0
        val transport: (HarborRequest) -> HarborReply = {calls++; HarborReply(200,"{}")}
        assertThrows(IllegalArgumentException::class.java) { HarborApi("https://foreign.supabase.co", "sb_publishable_fixture", identity(), transport, {100L}, {"nonce"}, {ByteArray(64)}) }
        assertThrows(IllegalArgumentException::class.java) { HarborApi("https://bfvybxkjxilntjgndsrm.supabase.co", "sb_secret_forbidden", identity(), transport, {100L}, {"nonce"}, {ByteArray(64)}) }
        assertEquals(0, calls)
    }
    @Test fun malformedClaimDoesNotReplaceBinding() {
        val identity = identity()
        val api = HarborApi("https://bfvybxkjxilntjgndsrm.supabase.co", "sb_publishable_fixture", identity, {HarborReply(200,"{\"deviceId\":\"bad\",\"familyId\":\"bad\",\"childId\":\"bad\"}")}, {100L}, {"nonce"}, {ByteArray(64)})
        assertThrows(IllegalArgumentException::class.java) { api.claim("123456", "public-spki") }
        assertEquals(binding, identity.binding)
    }
    @Test fun anonymousSignupRejectsParentCredentialResponse() {
        val api = HarborApi("https://bfvybxkjxilntjgndsrm.supabase.co", "sb_publishable_fixture", identity(), {HarborReply(200,"{\"access_token\":\"parent\",\"refresh_token\":\"refresh\",\"expires_in\":3600,\"user\":{\"is_anonymous\":false}}")}, {100L}, {"nonce"}, {ByteArray(64)})
        assertThrows(IllegalArgumentException::class.java) { api.anonymousSignup() }
    }
}
