package dev.stmedrano.harbor.parent.child

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import kotlinx.coroutines.test.runTest
import java.util.Base64

class ChildProtocolTest {
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val device = "22222222-2222-4222-8222-222222222222"
    private val family = "33333333-3333-4333-8333-333333333333"
    private val child = "44444444-4444-4444-8444-444444444444"
    private class Signer : ChildSigner {
        var proof: ByteArray? = null
        override fun exists() = true
        override fun publicKeySpki() = "public-spki"
        override fun sign(bytes: ByteArray): ByteArray { proof = bytes; return ByteArray(64) { 1 } }
        override fun delete() {}
    }
    @Test fun signedSyncCanonicalBytesPreserveExistingWireContract() {
        val proof = canonicalChildProof("POST", "device-sync", "device-id", "{}".toByteArray(), 124L, "another-nonce")
        assertEquals("POST\ndevice-sync\ndevice-id\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\n124\nanother-nonce", proof.toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) {
            canonicalChildProof("POST", "device-sync\nother-operation", "device-id", byteArrayOf(), 124L, "nonce")
        }
    }
    @Test fun childKeySignatureIsP1363AndVerifiesIndependently() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val bytes = "Harbor child wire proof".toByteArray()
        val signer = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(bytes) }
        val raw = childDerToP1363(signer.sign())
        assertEquals(64, raw.size)
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format").apply { initVerify(pair.public); update(bytes) }
        assertTrue(verifier.verify(raw))
        for (der in listOf(byteArrayOf(), byteArrayOf(0x30, 6, 0x02, 1, -1, 0x02, 1, 1),
            byteArrayOf(0x30, 6, 0x02, 1, 1, 0x02, 1, 1, 0))) {
            assertThrows(IllegalArgumentException::class.java) { childDerToP1363(der) }
        }
    }
    @Test fun signedSyncUsesOnlyChildBearerKeyAndExactBody() = runTest {
        val requests = mutableListOf<ChildRequest>(); val signer = Signer()
        val api = ChildApi("https://parent-ci.invalid", "sb_publishable_fixture", {
            requests += it; ChildReply(200, "{\"desiredState\":{},\"desiredStateVersion\":2,\"commands\":[]}")
        }, { 1000L }, { "fresh-nonce" }, signer)
        val session = ChildAuthSession(ChildCredentials("child-access", "child-refresh", 5000), owner, true)
        assertEquals(2L, api.sync(ChildBinding(device, family, child), session))
        val sent = requests.single()
        assertEquals("https://parent-ci.invalid/functions/v1/device-sync", sent.url)
        assertEquals("POST", sent.method)
        assertEquals("{}", sent.body.toString(Charsets.UTF_8))
        assertEquals("Bearer child-access", sent.headers["Authorization"])
        assertEquals(device, sent.headers["X-Harbor-Device-Id"])
        assertEquals("1000", sent.headers["X-Harbor-Timestamp"])
        assertEquals("fresh-nonce", sent.headers["X-Harbor-Nonce"])
        assertArrayEquals(ByteArray(64) { 1 }, Base64.getDecoder().decode(sent.headers["X-Harbor-Signature"]))
        assertEquals("POST\ndevice-sync\n$device\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\n1000\nfresh-nonce",
            checkNotNull(signer.proof).toString(Charsets.UTF_8))
    }
    @Test fun parentAuthenticationResponseIsRejected() = runTest {
        val api = ChildApi("https://parent-ci.invalid", "sb_publishable_fixture", {
            ChildReply(200, "{\"access_token\":\"parent-access\",\"refresh_token\":\"parent-refresh\",\"expires_at\":5000,\"user\":{\"id\":\"$owner\",\"is_anonymous\":false}}")
        }, { 1000L }, { "nonce" }, Signer())
        try { api.anonymousSignup(); fail("Parent identity must not enter child session") }
        catch (_: IllegalStateException) {}
    }
    @Test fun refreshRejectsChangedAnonymousOwner() = runTest {
        val api = ChildApi("https://parent-ci.invalid", "sb_publishable_fixture", {
            ChildReply(200, "{\"access_token\":\"other-access\",\"refresh_token\":\"other-refresh\",\"expires_at\":5000,\"user\":{\"id\":\"55555555-5555-4555-8555-555555555555\",\"is_anonymous\":true}}")
        }, { 1000L }, { "nonce" }, Signer())
        try { api.refresh(ChildAuthSession(ChildCredentials("child-access", "child-refresh", 999), owner, true)); fail("Owner must remain bound") }
        catch (_: IllegalStateException) {}
    }
}
