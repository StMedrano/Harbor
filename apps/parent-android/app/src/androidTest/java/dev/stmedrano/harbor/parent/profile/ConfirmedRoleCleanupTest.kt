package dev.stmedrano.harbor.parent.profile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.BuildConfig
import dev.stmedrano.harbor.parent.MainActivity
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.child.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ConfirmedRoleCleanupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun confirmedRemovalReopensChooserAndParentAuthentication() = runBlocking {
        check(BuildConfig.CI_FIXTURE)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = EncryptedChildStore(context)
        val key = ChildDeviceKey()
        val hint = AndroidProfileStore(context)
        val parent = SecureAuthStore.open(context)
        val target = ChildBinding("22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
        val session = ChildAuthSession(ChildCredentials("synthetic-access", "synthetic-refresh", 5000), "11111111-1111-4111-8111-111111111111", true)
        val backend = object : ChildBackend {
            override suspend fun anonymousSignup(): ChildAuthSession = error("No live Auth in fixture")
            override suspend fun refresh(session: ChildAuthSession): ChildAuthSession = error("No live refresh in fixture")
            override suspend fun claim(code: String, publicKeySpki: String, session: ChildAuthSession): ChildBinding = error("No live claim in fixture")
            override suspend fun sync(binding: ChildBinding, session: ChildAuthSession): Long = error("No live sync in fixture")
            override suspend fun registerFcm(binding: ChildBinding, token: String, session: ChildAuthSession) = error("No live provider in fixture")
        }
        try {
            parent.clear(); store.clear(); key.delete(); hint.clear(); key.publicKeySpki()
            store.save(ChildRecord(session, target)); hint.write(ProfileRole.CHILD)
            val repository = ChildRepository(backend, store, key, { 1000 })
            repository.restore(); repository.confirmRevocation(target)
            repository.clearAfterConfirmedRevocation(); hint.clear()
            assertFalse(EncryptedChildStore.hasRecords(context))
            assertFalse(EncryptedChildStore(context).hasHistory); assertFalse(key.exists())
            val graph = context.applicationContext as ParentApplication
            graph.profiles.restore()
            compose.waitUntil(5000) { graph.profiles.state.value == ProfileState.Setup }
            compose.onNodeWithText("Parent").assertExists().performScrollTo().performClick()
            compose.onNodeWithText("Email").assertExists()
            compose.onNodeWithText("Password").assertExists()
            compose.onNodeWithText("Settings").assertDoesNotExist()
            assertFalse(graph.hasChildSetup())
        } finally { parent.clear(); store.clear(); key.delete(); hint.clear() }
        Unit
    }
}
