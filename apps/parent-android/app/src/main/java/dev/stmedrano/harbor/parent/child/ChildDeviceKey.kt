package dev.stmedrano.harbor.parent.child

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ChildDeviceKey(private val alias: String = ALIAS) : ChildSigner {
    private fun store() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized override fun exists() = store().containsAlias(alias)
    @Synchronized override fun publicKeySpki(): String {
        if (!exists()) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1")).setDigests(KeyProperties.DIGEST_SHA256).build())
            generator.generateKeyPair()
        }
        return Base64.getEncoder().encodeToString(store().getCertificate(alias).publicKey.encoded)
    }
    @Synchronized override fun sign(bytes: ByteArray): ByteArray {
        check(exists()) { "Claimed child key unavailable" }
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(store().getKey(alias, null) as PrivateKey)
        signature.update(bytes)
        return childDerToP1363(signature.sign())
    }
    @Synchronized override fun delete() { store().deleteEntry(alias) }
    companion object { const val ALIAS = "harbor-family-child-p256-v1" }
}
