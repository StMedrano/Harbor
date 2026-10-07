package dev.stmedrano.harbor.parent.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.MainActivity
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore

class KeystorePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    // CI runs seed and restore in separate instrumentation processes, with an
    // explicit force-stop between them. These values are synthetic and offline.
    @Test fun seedColdStart() = runBlocking {
        val store = SecureAuthStore.open(context)
        store.clear()
        EncryptedSessionManager(store).saveSession(UserSession("instrumented-access", "instrumented-refresh", expiresIn = 3600, tokenType = "bearer"))
        EncryptedCodeVerifierCache(store).saveCodeVerifier("instrumented-verifier")
        store.transaction = AuthTransaction(AuthKind.RECOVERY, "parent@example.invalid", null, 1234)
        val raw = context.getSharedPreferences("harbor-secure-auth", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("instrumented"))
        assertFalse(raw.contains("parent@example"))
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(KeystoreCipher.ALIAS, null)
        assertNull(key.encoded)
    }

    @Test fun restoreColdStart() = runBlocking {
        val store = SecureAuthStore.open(context)
        assertEquals("instrumented-refresh", EncryptedSessionManager(store).loadSession().refreshToken)
        assertEquals("instrumented-verifier", EncryptedCodeVerifierCache(store).loadCodeVerifier())
        assertEquals("parent@example.invalid", store.transaction?.expectedEmail)
        store.clear()
    }

    @Test fun keyLossClearsCiphertextAndAllowsFreshSignIn() = runBlocking {
        val store = SecureAuthStore.open(context)
        store.clear()
        EncryptedCodeVerifierCache(store).saveCodeVerifier("instrumented-verifier")
        store.transaction = AuthTransaction(AuthKind.SIGNUP, "parent@example.invalid", "subject", 1234)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(KeystoreCipher.ALIAS) }
        assertThrows(AuthStorageLost::class.java) { store.transaction }
        assertTrue(context.getSharedPreferences("harbor-secure-auth", Context.MODE_PRIVATE).all.isEmpty())
        EncryptedCodeVerifierCache(store).saveCodeVerifier("new-verifier")
        assertEquals("new-verifier", EncryptedCodeVerifierCache(SecureAuthStore.open(context)).loadCodeVerifier())
        store.clear()
    }

    @Test fun callbackIsScrubbedBeforeActivityCanAcceptIt() {
        val intent = Intent(context, MainActivity::class.java).setData(Uri.parse("harbor-parent://auth/callback#access_token=synthetic-secret"))
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            scenario.onActivity { assertNull(it.intent.data); assertNull(it.intent.clipData) }
        }
    }
}
