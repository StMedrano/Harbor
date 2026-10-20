package dev.stmedrano.harbor.parent.controls

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.ui.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Synthetic policy only; fixture builds have no controls runtime. */
class ControlsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = ZonedDateTime.of(2026, 10, 20, 12, 0, 0, 0, ZoneId.of("America/Chicago"))
    private val policy = ControlsV1(blockedApps = listOf("com.zhiliaoapp.musically"), appLimits = listOf(AppLimit("com.google.android.youtube", 60)))

    @Test fun childSeesRulesAndStatusButNoWayToTurnThemOff() {
        var taps = 0
        val ui = mutableStateOf(ChildControlsUi(policy, false, mapOf("com.zhiliaoapp.musically" to "TikTok", "com.google.android.youtube" to "YouTube"),
            mapOf("com.google.android.youtube" to 25 * 60_000L), now) { taps++ })
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { ControlsPanel(ui.value) } } }
        compose.onNodeWithText("Blocked: TikTok.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("YouTube: 60 min a day — 25 min left today").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("App controls are off", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Turn on app controls").performScrollTo().performClick()
        assertEquals(1, taps)
        for (word in listOf("Turn off", "Disable", "Stop", "Pause controls")) compose.onNodeWithText(word, substring = true).assertDoesNotExist()

        compose.runOnIdle { ui.value = ui.value.copy(enabled = true) }
        compose.onNodeWithText("App controls are on.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Turn on app controls").assertDoesNotExist()
    }

    @Test fun serviceConfigCannotReadWindowContent() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        val mine = manager.installedAccessibilityServiceList.firstOrNull { it.resolveInfo.serviceInfo.packageName == context.packageName }
            ?: return // Fixture builds do not declare the service.
        val canRetrieve = mine.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT
        assertEquals(0, canRetrieve)
        assertFalse(mine.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0)
    }
}
