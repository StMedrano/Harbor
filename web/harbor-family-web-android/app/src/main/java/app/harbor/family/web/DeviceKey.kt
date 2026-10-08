package app.harbor.family.web

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The child device's identity key: ECDSA P-256 generated inside the Android Keystore. The private
 * key cannot leave the device. The server verifies signatures with WebCrypto, which expects the raw
 * r||s form (64 bytes), while Android produces ASN.1 DER, so sign() converts.
 */
object DeviceKey {
    private const val ALIAS = "harbor_device_p256"

    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    /** Creates a fresh key (replacing any old one) and returns its public key as base64 SPKI. */
    fun regenerate(): String {
        delete()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        gen.initialize(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build(),
        )
        gen.generateKeyPair()
        return publicKeySpki() ?: error("device key was not created")
    }

    fun publicKeySpki(): String? =
        store().getCertificate(ALIAS)?.publicKey?.encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }

    fun delete() { runCatching { store().deleteEntry(ALIAS) } }

    /** Signs UTF-8 [message] with SHA-256/ECDSA and returns base64 raw r||s. */
    fun sign(message: String): String {
        val key = store().getKey(ALIAS, null) as? PrivateKey ?: error("no device key")
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(key); update(message.toByteArray(Charsets.UTF_8)); sign()
        }
        return Base64.encodeToString(derToRaw(der), Base64.NO_WRAP)
    }

    /** ASN.1 DER ECDSA signature (SEQUENCE { INTEGER r, INTEGER s }) to fixed-width r||s. */
    fun derToRaw(der: ByteArray, size: Int = 32): ByteArray {
        var i = 0
        require(der.size >= 8 && der[i++] == 0x30.toByte()) { "not a DER sequence" }
        var len = der[i++].toInt() and 0xff
        if (len and 0x80 != 0) { // long-form length
            val n = len and 0x7f
            len = 0
            repeat(n) { len = (len shl 8) or (der[i++].toInt() and 0xff) }
        }
        require(len == der.size - i) { "bad DER length" }
        fun integer(): ByteArray {
            require(der[i++] == 0x02.toByte()) { "expected INTEGER" }
            val l = der[i++].toInt() and 0xff
            var start = i
            val end = i + l
            while (start < end - 1 && der[start] == 0.toByte()) start++ // strip sign padding
            i = end
            val v = der.copyOfRange(start, end)
            require(v.size <= size) { "integer too large" }
            return ByteArray(size - v.size) + v
        }
        val r = integer()
        val s = integer()
        return r + s
    }
}
