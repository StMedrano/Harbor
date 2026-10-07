package dev.stmedrano.harbor.parent.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SecurityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun nativeLargeTextMfaRequiresDeliberateRetryAndSensitiveWindowProtection() {
        assertEquals(1.8f, InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration.fontScale, 0.01f)
        val actor = ParentIdentity("synthetic-parent", "synthetic-session")
        val date = "2026-01-01T00:00:00Z"
        val family = FamilyState(FamilySnapshot(FamilyV1(1, "family", "Family", "UTC", date, date),
            FamilyMemberV1(1, "member", "family", actor.userId, "owner", "active", date, date),
            listOf(ChildV1(1, "child", "family", "Child", date, date)),
            listOf(DevicePublicV1(1, "device", "family", "child", "Phone", "standard", "active", null, date, date)), 1))
        var calls = 0; var verified = false; var refreshed = 0
        val mfa = object : MfaGateway {
            override suspend fun listFactors() = emptyList<String>()
            override suspend fun enrollTotp() = TotpEnrollment("factor", "SYNTHETIC-ONLY", "otpauth://totp/synthetic")
            override suspend fun challenge(factorId: String, code: String) { assertEquals("123456", code); verified = true }
        }
        val model = SecurityViewModel(mfa, { actor }, { family }, { _, _ -> calls++; if (!verified) throw MfaRequired() }, { refreshed++ })
        model.requestRevocation("family", "device")
        var showSecurity by mutableStateOf(true)
        compose.setContent { HarborTheme { ParentApp {
            if (showSecurity) SecurityScreen(model, onBack = { showSecurity = false })
            else androidx.compose.material3.Text("Family overview")
        } } }
        compose.waitForIdle()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.onNodeWithText("Confirm revocation").performScrollTo().performClick()
        compose.waitUntil(5000) { model.state.value.phase == SecurityPhase.STEP_UP }
        compose.onNodeWithText("Set up authenticator").performScrollTo().performClick()
        compose.waitUntil(5000) { model.state.value.enrollment != null }
        compose.onNodeWithText("Authenticator code").performScrollTo().performTextInput("123456")
        compose.onNodeWithText("Authenticator code").performImeAction()
        compose.onNodeWithText("Verify authenticator").performScrollTo().performClick()
        compose.waitUntil(5000) { model.state.value.phase == SecurityPhase.READY_TO_RETRY }
        assertEquals(1, calls)
        assertNull(model.state.value.enrollment)
        compose.onNodeWithText("Retry revocation").performScrollTo().performClick()
        // ACCEPTED confirms revocation before the independent refresh finishes.
        // Wait for the operation boundary before asserting its refresh side effect.
        compose.waitUntil(5000) { model.state.value.phase == SecurityPhase.ACCEPTED && !model.state.value.busy }
        assertEquals(2, calls); assertEquals(1, refreshed)
        compose.onNodeWithText("Back to family").performScrollTo().performClick()
        compose.onNodeWithText("Family overview").assertIsDisplayed()
        assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE)
        assertEquals(SecurityPhase.IDLE, model.state.value.phase)
        assertNull(model.state.value.enrollment)
    }
}
