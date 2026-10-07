package dev.stmedrano.harbor.parent.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.MainActivity
import dev.stmedrano.harbor.parent.R
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyStore
import org.xmlpull.v1.XmlPullParser

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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // ActivityScenario matches the original URI on every lifecycle event,
        // so it cannot observe an Activity that correctly scrubs that URI.
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        try {
            val intent = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse("harbor-parent://auth/callback#access_token=synthetic-secret"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(intent)
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
            assertNotNull("Cold callback Activity must start", activity)
            instrumentation.runOnMainSync {
                assertNull(activity.intent.data)
                assertNull(activity.intent.clipData)
                val next = Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW)
                    .setData(Uri.parse("harbor-parent://auth/callback?type=recovery#access_token=synthetic-secret"))
                next.clipData = android.content.ClipData.newPlainText("callback", "synthetic-secret")
                instrumentation.callActivityOnNewIntent(activity, next)
                assertNull(activity.intent.data)
                assertNull(activity.intent.clipData)
                activity.finish()
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun credentialsAreExcludedFromLegacyCloudAndDeviceTransfer() {
        assertEquals(0, context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_ALLOW_BACKUP)
        val rules = listOf(R.xml.backup_rules to setOf("full-backup-content"), R.xml.data_extraction_rules to setOf("cloud-backup", "device-transfer"))
        for ((resource, modes) in rules) {
            val excluded = mutableSetOf<String>()
            var mode = ""
            context.resources.getXml(resource).use { parser ->
                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    if (parser.eventType == XmlPullParser.START_TAG) {
                        if (parser.name in modes) mode = parser.name
                        if (parser.name == "exclude" && parser.getAttributeValue(null, "domain") == "sharedpref" &&
                            parser.getAttributeValue(null, "path") == ".") excluded += mode
                    }
                    parser.next()
                }
            }
            assertEquals(modes, excluded)
        }
    }
}
