package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.auth.*
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse

class ParentSessionNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun store() = SecureAuthStore(object : AuthValues {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun clear() = values.clear()
    }, object : AuthCipher {
        override fun encrypt(slot: String, value: ByteArray) = value
        override fun decrypt(slot: String, value: ByteArray) = value
    })

    private class Gateway : AuthGateway {
        var session: UserSession? = null
        private fun verifiedSession() = UserSession("synthetic", "synthetic", expiresIn = 3600, tokenType = "bearer").also { session = it }
        override suspend fun signIn(email: String, password: String) = verifiedSession()
        override suspend fun restoreStoredSession() = session
        override suspend fun fetchVerifiedIdentity(session: UserSession, expectedEmail: String?, expectedUserId: String?) = ParentIdentity("synthetic-parent", "synthetic-session")
        override suspend fun clearLocalSession() { session = null }
        override suspend fun signOutCurrent() { session = null }
        override suspend fun requestRecovery(email: String) {}
        override suspend fun exchangeCode(code: String) = verifiedSession()
        override suspend fun changePassword(password: String) {}
        override suspend fun signUp(email: String, password: String) = "synthetic-parent"
        override suspend fun refresh() = verifiedSession()
    }

    private fun show(repository: ParentAuthRepository, initialCallback: String? = null) {
        compose.setContent {
            val identity by repository.identity.collectAsState()
            var authenticationOpen by remember { mutableStateOf(true) }
            var callback by remember { mutableStateOf(initialCallback) }
            HarborTheme { ParentApp(showBrand = false) {
                ParentSessionContent(identity, authenticationOpen || callback != null,
                    authentication = { AuthScreen(repository, callback,
                        onAuthenticated = { authenticationOpen = false }, onCallbackConsumed = { callback = null }) },
                    parentMenu = { TextButton({}) { Text("Settings") } },
                    parentContent = { Text("Parent controls") })
            } }
        }
    }

    @Test fun signInAndSessionLossSeparateLoginFromParentMenu() = runBlocking {
        val repository = ParentAuthRepository(Gateway(), store())
        show(repository)
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Working…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Email").performScrollTo().performTextInput("parent@example.invalid")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("synthetic-password")
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Parent controls").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Welcome back").assertDoesNotExist()
        repository.clearLocal()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Welcome back").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
    }

    @Test fun restoredSessionOpensParentAreaWithoutLogin() = runBlocking {
        val gateway = Gateway()
        gateway.signIn("parent@example.invalid", "synthetic-password")
        show(ParentAuthRepository(gateway, store()))
        compose.waitUntil(5000) { compose.onAllNodesWithText("Parent controls").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Settings").assertExists()
        compose.onNodeWithText("Welcome back").assertDoesNotExist()
    }

    @Test fun recoveryKeepsMenuHiddenUntilDeliberateBack() = runBlocking {
        val gateway = Gateway()
        gateway.signIn("parent@example.invalid", "synthetic-password")
        val repository = ParentAuthRepository(gateway, store())
        repository.beginRecovery("parent@example.invalid")
        show(repository, "harbor-parent://auth/callback?code=synthetic")
        compose.waitUntil(5000) { compose.onAllNodesWithText("New password").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Parent controls").fetchSemanticsNodes().size == 1 }
        assertFalse(repository.hasVerifiedRecovery())
        compose.onNodeWithText("New password").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertExists()
        Unit
    }
}
