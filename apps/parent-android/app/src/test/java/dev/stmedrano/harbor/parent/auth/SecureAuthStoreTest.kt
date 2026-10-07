package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class SecureAuthStoreTest {
    private class Values : AuthValues {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun clear() { data.clear() }
    }
    private class Aes : AuthCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(slot: String, value: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(slot.toByteArray())
            return cipher.iv + cipher.doFinal(value)
        }
        override fun decrypt(slot: String, value: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, 12)))
            cipher.updateAAD(slot.toByteArray())
            return cipher.doFinal(value.copyOfRange(12, value.size))
        }
    }

    @Test fun recreationRetainsEncryptedSdkSessionVerifierAndTransaction() = runTest {
        val values = Values()
        val cipher = Aes()
        val store = SecureAuthStore(values, cipher)
        val session = UserSession("access-secret", "refresh-secret", expiresIn = 3600, tokenType = "bearer")
        EncryptedSessionManager(store).saveSession(session)
        EncryptedCodeVerifierCache(store).saveCodeVerifier("verifier-secret")
        val transaction = AuthTransaction(AuthKind.RECOVERY, "parent@example.invalid", null, 1234)
        store.transaction = transaction
        assertFalse(values.data.values.any { it.contains("secret") || it.contains("parent@") })
        val recreated = SecureAuthStore(values, cipher)
        assertEquals(session, EncryptedSessionManager(recreated).loadSession())
        assertEquals("verifier-secret", EncryptedCodeVerifierCache(recreated).loadCodeVerifier())
        assertEquals(transaction, recreated.transaction)
    }

    @Test fun keyLossClearsAllAuthState() = runTest {
        val values = Values()
        val original = SecureAuthStore(values, Aes())
        EncryptedCodeVerifierCache(original).saveCodeVerifier("verifier")
        original.transaction = AuthTransaction(AuthKind.SIGNUP, "parent@example.invalid", "subject", 12)
        val lost = SecureAuthStore(values, Aes())
        assertThrows(AuthStorageLost::class.java) { lost.transaction }
        assertTrue(values.data.isEmpty())
        assertNull(EncryptedCodeVerifierCache(lost).loadCodeVerifier())
    }

    @Test fun ciphertextCannotBeMovedBetweenSlots() = runTest {
        val values = Values()
        val store = SecureAuthStore(values, Aes())
        EncryptedCodeVerifierCache(store).saveCodeVerifier("verifier")
        values.data["transaction"] = values.data.getValue("verifier")
        assertThrows(AuthStorageLost::class.java) { store.transaction }
        assertTrue(values.data.isEmpty())
    }
}
