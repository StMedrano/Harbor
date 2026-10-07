package dev.stmedrano.harbor.parent.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import dev.stmedrano.harbor.parent.auth.*
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking

class AuthScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun recoveryScreenRequiresVerifiedCallbackAndBackClearsAuthorization() = runBlocking {
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
            override suspend fun restoreStoredSession(): UserSession? = null
            override suspend fun clearLocalSession() {}
            override suspend fun requestRecovery(email: String) {}
            override suspend fun exchangeCode(code: String) = UserSession("synthetic", "synthetic", expiresIn = 3600, tokenType = "bearer")
            override suspend fun fetchVerifiedIdentity(session: UserSession, expectedEmail: String?, expectedUserId: String?) = ParentIdentity("synthetic-user", "synthetic-session")
            override suspend fun signIn(email: String, password: String) = error("Not used")
            override suspend fun signUp(email: String, password: String) = error("Not used")
            override suspend fun changePassword(password: String) = error("Not used")
            override suspend fun refresh() = error("Not used")
            override suspend fun signOutCurrent() {}
        }
        val repository = ParentAuthRepository(gateway, store)
        val callback = mutableStateOf<String?>(null)
        compose.setContent { HarborTheme { ParentApp { AuthScreen(repository, callback.value) { callback.value = null } } } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Working…").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Forgot password?").performScrollTo().performClick()
        compose.onNodeWithText("Email").performScrollTo().performTextInput("parent@example.invalid")
        compose.onNodeWithText("Request recovery email").performClick()
        compose.waitUntil(5000) { store.transaction != null }
        compose.onNodeWithText("New password").assertDoesNotExist()
        assertFalse(repository.hasVerifiedRecovery())
        compose.runOnIdle { callback.value = "harbor-parent://auth/callback?code=synthetic" }
        compose.waitUntil(5000) { compose.onAllNodesWithText("New password").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("New password").performScrollTo().performTextInput("synthetic-new")
        compose.onNodeWithText("Back to sign in").performScrollTo().performClick()
        compose.waitUntil(5000) { store.transaction == null }
        assertNull(store.transaction)
        assertFalse(repository.hasVerifiedRecovery())
        compose.onNodeWithText("New password").assertDoesNotExist()
        compose.onNodeWithText("Password").assertExists()
        Unit
    }

    @Test fun offlineFixtureCannotSendCredentialsAndRecoveryControlsAreLabelled() {
        compose.setContent { HarborTheme { ParentApp { AuthScreen(null, null) {} } } }
        compose.onNodeWithText("Email").assertIsNotEnabled()
        compose.onNodeWithText("Password").assertIsNotEnabled()
        compose.onNodeWithText("New password").assertDoesNotExist()
        compose.onNodeWithText("Request recovery email").assertDoesNotExist()
        compose.onNodeWithTag("auth-submit").assertIsNotEnabled()
        compose.onNodeWithText("Forgot password?").performScrollTo().performClick()
        compose.onNodeWithText("Request recovery email").assertIsNotEnabled()
        compose.onNodeWithText("Password").assertDoesNotExist()
        compose.onNodeWithText("New password").assertDoesNotExist()
        compose.onNodeWithText("Back to sign in").performScrollTo().performClick()
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("Confirm password").assertExists()
        compose.onNodeWithTag("auth-submit").assertIsNotEnabled()
        compose.onNodeWithText("Forgot password?").assertDoesNotExist()
    }

    @Test fun largeTextAuthNavigationKeepsRecoverySeparate() = offlineFixtureCannotSendCredentialsAndRecoveryControlsAreLabelled()

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
        compose.onNodeWithText("Email").performScrollTo().performTextInput("parent@example.invalid")
        compose.onNodeWithText("Password").performScrollTo().performTextInput("synthetic-wrong")
        compose.onNodeWithTag("auth-submit").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Request failed. Check your connection, or cancel the email flow and request a fresh link.").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Parent session verified").assertDoesNotExist()
        compose.onNodeWithText("Create account").performScrollTo().performClick()
        compose.onNodeWithText("Password").performScrollTo().performTextInput("synthetic-new")
        compose.onNodeWithText("Confirm password").performScrollTo().performTextInput("different")
        compose.onNodeWithTag("auth-submit").assertIsNotEnabled()
        compose.onNodeWithText("Confirm password").performTextClearance()
        compose.onNodeWithText("Confirm password").performTextInput("synthetic-new")
        compose.onNodeWithTag("auth-submit").assertIsEnabled()
        compose.onNodeWithContentDescription("Show password").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Hide password").assertExists()
        Unit
    }
}

