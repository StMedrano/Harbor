package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.child.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class ChildPairingScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private class Store : ChildStore {
        var record: ChildRecord? = null
        override var claimPending = false
        override fun load() = record
        override fun save(record: ChildRecord) { this.record = record }
        override fun clear() { record = null }
    }
    private val binding = ChildBinding("22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private var claims = 0
    private fun repository(store: Store, lost: Boolean): ChildRepository {
        val backend = object : ChildBackend {
            override suspend fun anonymousSignup() = ChildAuthSession(ChildCredentials("fixture-access", "fixture-refresh", 5000),
                "11111111-1111-4111-8111-111111111111", true)
            override suspend fun refresh(session: ChildAuthSession) = session
            override suspend fun claim(code: String, publicKeySpki: String, session: ChildAuthSession): ChildBinding {
                claims++
                assertEquals("123456", code)
                assertTrue(store.claimPending)
                if (lost) throw IOException("offline fixture")
                return binding
            }
            override suspend fun sync(binding: ChildBinding, session: ChildAuthSession) = 1L
            override suspend fun registerFcm(binding: ChildBinding, token: String, session: ChildAuthSession) {}
        }
        val key = object : ChildSigner {
            override fun exists() = true
            override fun publicKeySpki() = "fixture-public-key"
            override fun sign(bytes: ByteArray) = ByteArray(64)
            override fun delete() {}
        }
        return ChildRepository(backend, store, key, { 1000 })
    }
    private fun show(repository: ChildRepository, confirmed: () -> Unit) {
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ChildPairingScreen(repository, confirmed)
        } } }
    }
    @Test fun confirmedClaimAloneOpensChildAndClearsCode() {
        val store = Store(); val repository = repository(store, false); var opened = 0
        show(repository) { assertEquals(binding, repository.binding.value); opened++ }
        compose.onNodeWithText("Pairing code").performTextInput("123456")
        compose.onNodeWithText("Connect phone").performClick()
        compose.waitUntil(5000) { opened == 1 }
        compose.onNodeWithText("123456").assertDoesNotExist()
        assertEquals(1, claims)
        assertFalse(store.claimPending)
    }
    @Test fun lostReplyDoesNotOpenDashboardOrPermitBlindRetry() {
        val store = Store(); val repository = repository(store, true); var opened = 0
        show(repository) { opened++ }
        compose.onNodeWithText("Pairing code").performTextInput("123456")
        compose.onNodeWithText("Connect phone").performClick()
        compose.waitUntil(5000) { repository.state.value is ChildSyncState.Blocked }
        compose.onNodeWithText("This phone needs a parent to check its setup.").assertExists()
        compose.onNodeWithText("Connect phone").assertDoesNotExist()
        compose.onNodeWithText("123456").assertDoesNotExist()
        assertEquals(0, opened); assertEquals(1, claims); assertTrue(store.claimPending)
    }
}
