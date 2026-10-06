package dev.stmedrano.harbor.acceptance

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class DeviceProofTest {
    @Test fun canonicalProofHashesExactEmptyBodyAndSeparatesSixFields() {
        val result = canonicalProof("post", "register-fcm", "device-id", byteArrayOf(), 123L, "fresh-nonce")
        assertEquals("POST\nregister-fcm\ndevice-id\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n123\nfresh-nonce", result.toString(Charsets.UTF_8))
    }
    @Test fun canonicalProofHashesObjectBytesWithoutReserializing() {
        val result = canonicalProof("POST", "device-sync", "device-id", "{}".toByteArray(), 124L, "another-nonce")
        assertEquals("POST\ndevice-sync\ndevice-id\n44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\n124\nanother-nonce", result.toString(Charsets.UTF_8))
    }
    @Test fun convertsPositiveIntegerPaddingToFixedWidth() {
        val r = byteArrayOf(0) + ByteArray(32) { 0x80.toByte() }
        val s = byteArrayOf(1)
        val der = byteArrayOf(0x30, 38, 0x02, 33) + r + byteArrayOf(0x02, 1) + s
        val raw = derToP1363(der)
        assertEquals(64, raw.size)
        assertArrayEquals(ByteArray(32) { 0x80.toByte() }, raw.copyOfRange(0, 32))
        assertArrayEquals(ByteArray(31) + byteArrayOf(1), raw.copyOfRange(32, 64))
    }
    @Test fun rejectsMalformedNegativeTrailingAndOversizedIntegers() {
        val malformed = listOf(
            byteArrayOf(), byteArrayOf(0x30, 6, 0x02, 1, -1, 0x02, 1, 1),
            byteArrayOf(0x30, 6, 0x02, 1, 1, 0x02, 1, 1, 0),
            byteArrayOf(0x30, 6, 0x02, 1, 0, 0x02, 1, 1),
            byteArrayOf(0x30, 39, 0x02, 34) + ByteArray(34) { 1 } + byteArrayOf(0x02, 1, 1)
        )
        for (der in malformed) assertThrows(IllegalArgumentException::class.java) { derToP1363(der) }
    }
    @Test fun convertedRealP256SignatureVerifiesWithIndependentJcaVerifier() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val message = "Harbor proof acceptance".toByteArray()
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(pair.private); signer.update(message)
        val raw = derToP1363(signer.sign())
        assertEquals(64, raw.size)
        val verifier = Signature.getInstance("SHA256withECDSAinP1363Format")
        verifier.initVerify(pair.public); verifier.update(message)
        assertTrue(verifier.verify(raw))
    }
}
