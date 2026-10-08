package dev.stmedrano.harbor.parent.profile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.BuildConfig
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.auth.*
import dev.stmedrano.harbor.parent.child.*
import dev.stmedrano.harbor.parent.ui.*
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class FamilyCompositionPersistenceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val target = ChildBinding("22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private val session = ChildAuthSession(ChildCredentials("synthetic-access", "synthetic-refresh", 5000), "11111111-1111-4111-8111-111111111111", true)
    private val noNetwork = object : ChildBackend {
        override suspend fun anonymousSignup(): ChildAuthSession = error("No live Auth in fixture")
        override suspend fun refresh(session: ChildAuthSession): ChildAuthSession = error("No live refresh in fixture")
        override suspend fun claim(code: String, publicKeySpki: String, session: ChildAuthSession): ChildBinding = error("No live claim in fixture")
        override suspend fun sync(binding: ChildBinding, session: ChildAuthSession): Long = error("No live sync in fixture")
        override suspend fun registerFcm(binding: ChildBinding, token: String, session: ChildAuthSession) = error("No live provider in fixture")
    }
    @Test fun seedConfirmedRevocationBeforeLocalErasure() = runBlocking {
        check(BuildConfig.CI_FIXTURE)
        val store = EncryptedChildStore(context, "family-crash-child")
        val key = ChildDeviceKey("family-crash-child-p256")
        store.clear(); key.delete(); key.publicKeySpki()
        store.save(ChildRecord(session, target, lastSuccessAt = 999, desiredVersion = 2))
        val repository = ChildRepository(noNetwork, store, key, { 1000 })
        repository.restore(); repository.confirmRevocation(target)
        assertTrue(checkNotNull(store.load()).revoked)
        assertTrue(key.exists()) // Simulated crash checkpoint: secrets still exist, but are revoked.
        AndroidProfileStore(context, "family-crash-profile").write(ProfileRole.CHILD)
    }
    @Test fun coldRevocationCheckpointCannotRestoreProtectedChild() = runBlocking {
        check(BuildConfig.CI_FIXTURE)
        val store = EncryptedChildStore(context, "family-crash-child")
        val key = ChildDeviceKey("family-crash-child-p256")
        val hint = AndroidProfileStore(context, "family-crash-profile")
        try {
            assertTrue(checkNotNull(store.load()).revoked); assertTrue(key.exists())
            val repository = ChildRepository(noNetwork, store, key, { 1000 })
            var starts = 0
            val coordinator = ProfileCoordinator(hint, { null }, {
                repository.restore()
                if (repository.state.value is ChildSyncState.Blocked) throw ProfileValidationFailure(ProfileBlock.INVALID_CREDENTIALS)
                repository.binding.value?.deviceId
            }, {}, { starts++ })
            coordinator.restore()
            assertEquals(ChildSyncState.Blocked(ChildFailure.REVOKED), repository.state.value)
            assertEquals(FamilyDestination.BLOCKED, FamilyEntryState(coordinator.state.value,
                childBinding = repository.binding.value, childState = repository.state.value).destination())
            assertNull(coordinator.currentLease()); assertEquals(0, starts)
            assertTrue(key.exists()); assertTrue(checkNotNull(store.load()).revoked)
        } finally { store.clear(); key.delete(); hint.clear() }
    }
    @Test fun seedAmbiguousPrimaryNamespaces() = runBlocking {
        check(BuildConfig.CI_FIXTURE)
        val parent = SecureAuthStore.open(context)
        val child = EncryptedChildStore(context)
        val key = ChildDeviceKey()
        parent.clear(); child.clear(); key.delete(); key.publicKeySpki()
        EncryptedSessionManager(parent).saveSession(UserSession("synthetic-parent-access", "synthetic-parent-refresh", expiresIn = 3600, tokenType = "bearer"))
        child.save(ChildRecord(session, target))
        AndroidProfileStore(context).write(ProfileRole.PARENT)
    }
    @Test fun coldAmbiguousStartupPreservesStoresAndHidesRuntime() = runBlocking {
        check(BuildConfig.CI_FIXTURE)
        val parent = SecureAuthStore.open(context)
        val child = EncryptedChildStore(context)
        val key = ChildDeviceKey()
        val hint = AndroidProfileStore(context)
        try {
            val graph = context.applicationContext as ParentApplication
            val state = withTimeout(10000) { graph.profiles.state.first { it != ProfileState.Transitioning } }
            assertEquals(ProfileState.Blocked(ProfileBlock.AMBIGUOUS), state)
            assertNull(graph.profiles.currentLease()); assertNull(graph.authRepository)
            val runtime = ParentApplication::class.java.getDeclaredField("runtime\$delegate").apply { isAccessible = true }.get(graph) as Lazy<*>
            assertFalse(runtime.isInitialized())
            assertEquals("synthetic-parent-refresh", EncryptedSessionManager(parent).loadSession().refreshToken)
            assertEquals(target, child.load()?.binding); assertTrue(key.exists())
            assertEquals(ProfileRole.PARENT, hint.read())
        } finally { parent.clear(); child.clear(); key.delete(); hint.clear() }
    }
}
