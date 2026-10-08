package dev.stmedrano.harbor.parent.profile

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.ui.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ParentApprovalUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Volatile private var signed = 0
    @Volatile private var verified = 0
    @Volatile private var removed = 0
    @Test fun approvalRequiresSeparateSignInMfaAndDeliberateRemoval() {
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ParentApprovalScreen({ email, password ->
                assertEquals("parent@example.invalid", email); assertEquals("synthetic-password", password); signed++; true
            }, { code -> assertEquals("123456", code); verified++; true }, { removed++; false }, {})
        } } }
        compose.onNodeWithText("Remove enrollment").assertDoesNotExist()
        compose.onNodeWithText("Parent email").performTextInput("parent@example.invalid")
        compose.onNodeWithText("Parent password").performTextInput("synthetic-password")
        compose.onNodeWithText("Continue as parent").performScrollTo().performClick()
        compose.waitUntil(5000) { signed == 1 && compose.onAllNodesWithText("Authenticator code").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Authenticator code").assertExists()
        compose.onNodeWithText("synthetic-password").assertDoesNotExist()
        compose.onNodeWithText("Remove enrollment").assertDoesNotExist()
        compose.onNodeWithText("Authenticator code").performTextInput("123456")
        compose.onNodeWithText("Verify parent approval").performScrollTo().performClick()
        compose.waitUntil(5000) { verified == 1 && compose.onAllNodesWithText("Remove enrollment").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Remove enrollment").performScrollTo().performClick()
        compose.waitUntil(5000) { removed == 1 && compose.onAllNodesWithText("Removal was not confirmed. This phone remains enrolled.").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithText("Removal was not confirmed. This phone remains enrolled.").assertExists()
        compose.onNodeWithText("Family", substring = false).assertDoesNotExist()
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) }
    }
    @Test fun backCancelsPendingApprovalAndRemovesSecretFields() {
        val holding = CompletableDeferred<Unit>()
        var canceled by mutableStateOf(false)
        val entered = java.util.concurrent.atomic.AtomicBoolean()
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            if (canceled) Text("Approval closed") else ParentApprovalScreen({ _, _ ->
                entered.set(true); holding.await(); true
            }, { true }, { removed++; true }, { canceled = true })
        } } }
        compose.onNodeWithText("Parent email").performTextInput("parent@example.invalid")
        compose.onNodeWithText("Parent password").performTextInput("synthetic-password")
        compose.onNodeWithText("Continue as parent").performScrollTo().performClick()
        compose.waitUntil(5000) { entered.get() }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Approval closed").assertExists()
        holding.complete(Unit); compose.waitForIdle()
        assertEquals(0, removed)
        compose.onNodeWithText("synthetic-password").assertDoesNotExist()
    }
}
