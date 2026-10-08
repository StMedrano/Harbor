package dev.stmedrano.harbor.parent.profile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.auth.EncryptedSessionManager
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.data.FamilyCacheRow
import dev.stmedrano.harbor.parent.data.ParentDatabase
import dev.stmedrano.harbor.parent.data.PendingChildRow
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProfileUpgradeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun parent04StoresSurviveProfileBootstrap() = runBlocking {
        val profile = AndroidProfileStore(context, "profile-upgrade-test")
        val auth = SecureAuthStore.open(context)
        val databaseName = "profile-upgrade-cache-test"
        context.deleteDatabase(databaseName)
        val database = ParentDatabase.open(context, databaseName)
        try {
            profile.clear(); auth.clear()
            EncryptedSessionManager(auth).saveSession(UserSession("offline-access", "offline-refresh", expiresIn = 3600, tokenType = "bearer"))
            database.familyCache().putSnapshot(FamilyCacheRow("parent-a", "family-a", "snapshot", 1234))
            database.familyCache().beginPending(PendingChildRow("parent-a", "family-a", "stable-key", "fingerprint", "Child"))
            var starts = 0
            // Offline boundary supplies verified owner; this tests storage migration,
            // not live Auth verification, which stays in the existing repository.
            val coordinator = ProfileCoordinator(profile, { "parent-a" }, { null }, {}, { starts++ })
            coordinator.restore()
            assertEquals(ProfileRole.PARENT, AndroidProfileStore(context, "profile-upgrade-test").read())
            assertEquals("parent-a", coordinator.currentLease()?.ownerId)
            assertEquals(1, starts)
            assertEquals("offline-refresh", EncryptedSessionManager(SecureAuthStore.open(context)).loadSession().refreshToken)
            assertEquals("snapshot", database.familyCache().getSnapshot("parent-a", "family-a")?.snapshotJson)
            assertEquals("stable-key", database.familyCache().getPending("parent-a", "family-a")?.idempotencyKey)
        } finally { profile.clear(); auth.clear(); database.close(); context.deleteDatabase(databaseName) }
    }
    @Test fun corruptModeHintDoesNotStartRuntime() = runBlocking {
        val name = "profile-corruption-test"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString("role", "unexpected").commit())
        try {
            var started = false
            val coordinator = ProfileCoordinator(AndroidProfileStore(context, name), { "parent" }, { null }, {}, { started = true })
            coordinator.restore()
            assertFalse(started)
            assertEquals(ProfileState.Blocked(ProfileBlock.STORAGE_UNAVAILABLE), coordinator.state.value)
            assertEquals("unexpected", prefs.getString("role", null))
        } finally { prefs.edit().clear().commit() }
    }
}
