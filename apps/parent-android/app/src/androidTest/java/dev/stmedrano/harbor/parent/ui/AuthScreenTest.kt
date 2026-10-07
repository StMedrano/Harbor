package dev.stmedrano.harbor.parent.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

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
}
