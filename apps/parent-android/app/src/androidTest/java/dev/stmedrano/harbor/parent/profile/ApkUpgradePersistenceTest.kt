package dev.stmedrano.harbor.parent.profile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.BuildConfig
import dev.stmedrano.harbor.parent.auth.EncryptedCodeVerifierCache
import dev.stmedrano.harbor.parent.auth.EncryptedSessionManager
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.data.FamilyCacheRow
import dev.stmedrano.harbor.parent.data.ParentDatabase
import dev.stmedrano.harbor.parent.data.PendingChildRow
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Run in separate processes around a real APK replacement, only on CI emulators. */
class ApkUpgradePersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun requireFixture(version: Int) {
        assertTrue("Never seed a connected development app", BuildConfig.CI_FIXTURE)
        assertEquals("dev.stmedrano.harbor.parent", context.packageName)
        assertEquals(version, BuildConfig.VERSION_CODE)
    }

    @Test fun seedVersion16() = runBlocking {
        requireFixture(16)
        val auth = SecureAuthStore.open(context)
        // A fresh emulator is required. Never overwrite an existing session.
        assertTrue(context.getSharedPreferences("harbor-secure-auth", Context.MODE_PRIVATE).all.isEmpty())
        assertNull(AndroidProfileStore(context).read())
        EncryptedSessionManager(auth).saveSession(UserSession("upgrade-access", "upgrade-refresh", expiresIn = 3600, tokenType = "bearer"))
        EncryptedCodeVerifierCache(auth).saveCodeVerifier("upgrade-pkce")
        AndroidProfileStore(context).write(ProfileRole.PARENT)
        val database = ParentDatabase.open(context)
        try {
            database.familyCache().putSnapshot(FamilyCacheRow("upgrade-owner", "upgrade-family", "upgrade-snapshot", 1234))
            database.familyCache().beginPending(PendingChildRow("upgrade-owner", "upgrade-family", "upgrade-stable-key", "upgrade-fingerprint", "Synthetic child"))
        } finally { database.close() }
        val raw = context.getSharedPreferences("harbor-secure-auth", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains("upgrade-refresh"))
        assertFalse(raw.contains("upgrade-pkce"))
    }

    @Test fun version17RetainsVersion16Data() = runBlocking {
        requireFixture(17)
        val auth = SecureAuthStore.open(context)
        assertEquals("upgrade-refresh", EncryptedSessionManager(auth).loadSession().refreshToken)
        assertEquals("upgrade-pkce", EncryptedCodeVerifierCache(auth).loadCodeVerifier())
        assertEquals(ProfileRole.PARENT, AndroidProfileStore(context).read())
        val database = ParentDatabase.open(context)
        try {
            assertEquals("upgrade-snapshot", database.familyCache().getSnapshot("upgrade-owner", "upgrade-family")?.snapshotJson)
            val pending = database.familyCache().getPending("upgrade-owner", "upgrade-family")
            assertEquals("upgrade-stable-key", pending?.idempotencyKey)
            assertEquals("upgrade-fingerprint", pending?.fingerprint)
        } finally { database.close() }
        // Deliberately retain every value for a second cold process verification.
    }
}
