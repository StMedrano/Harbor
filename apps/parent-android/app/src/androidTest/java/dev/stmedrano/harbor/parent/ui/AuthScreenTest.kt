package dev.stmedrano.harbor.parent.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import dev.stmedrano.harbor.parent.auth.*
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking

class AuthScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun offlineFixtureCannotSendCredentialsAndRecoveryControlsAreLabelled() {
        compose.setContent { HarborTheme { ParentApp { AuthScreen(null, null) {} } } }
        compose.onNodeWithText("Email").assertIsNotEnabled()
        compose.onNodeWithText("Password").assertIsNotEnabled()
        compose.onNodeWithText("Sign in").assertIsNotEnabled()
        compose.onNodeWithText("Create account").assertIsNotEnabled()
        compose.onNodeWithText("Request recovery email").assertIsNotEnabled()
        compose.onNodeWithText("New password").assertIsNotEnabled()
        compose.onNodeWithText("Confirm new password").assertIsNotEnabled()
        compose.onNodeWithText("Update password").assertIsNotEnabled()
    }

    @Test fun failedSignInCannotLeaveVerifiedSessionTitleVisible() = runBlocking {
        val store = SecureAuthStore(object : AuthValues {
            val values = mutableMapOf<String, String>()
            override fun read(key: String) = values[key]
            override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
            override fun clear() = values.clear()
        }, object : AuthCipher {
            override fun encrypt(slot: String, value: ByteArray) = value
            override fun decrypt(slot: String, value: ByteArray) = value
        })
        val gateway = object : AuthGateway {
            var session: UserSession? = UserSession("synthetic", "synthetic", expiresIn = 3600, tokenType = "bearer")
            override suspend fun signIn(email: String, password: String): UserSession = error("Network sign-in rejected")
            override suspend fun restoreStoredSession() = session
            override suspend fun fetchVerifiedIdentity(session: UserSession, expectedEmail: String?, expectedUserId: String?) = ParentIdentity("synthetic-user", "synthetic-session")
            override suspend fun clearLocalSession() { session = null }
            override suspend fun signOutCurrent() { session = null }
            override suspend fun refresh() = error("Not used")
            override suspend fun signUp(email: String, password: String) = error("Not used")
            override suspend fun requestRecovery(email: String) = error("Not used")
            override suspend fun exchangeCode(code: String) = error("Not used")
            override suspend fun changePassword(password: String) = error("Not used")
        }
        val repository = ParentAuthRepository(gateway, store)
        compose.setContent { HarborTheme { ParentApp { AuthScreen(repository, null) {} } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Parent session verified").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Email").performTextInput("parent@example.invalid")
        compose.onNodeWithText("Password").performTextInput("synthetic-wrong")
        compose.onNodeWithText("Sign in").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Request failed. Check your connection, or cancel the email flow and request a fresh link.").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Parent session verified").assertDoesNotExist()
    }
}
