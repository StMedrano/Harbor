package dev.stmedrano.harbor.parent.alerts

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic data only; fixture builds have no capture runtime and these tests never read a real message. */
class AlertsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun alert(category: AlertCategory, at: String, saved: Boolean = false) =
        MessageAlert("00000000-0000-4000-8000-000000000001", "WhatsApp", category, 1, at, saved)

    @Test fun childPanelDisclosesWhatIsWatchedAndNeverOffersADisableControl() {
        var notificationTaps = 0
        val ui = mutableStateOf(ChildAlertsUi(AlertAccess(false, false), AlertHostState(), { notificationTaps++ }, {}, {}))
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { MessageAlertsPanel(ui.value) } } }
        compose.onNodeWithText("What Harbor watches").assertExists()
        compose.onNodeWithText("never sent the message text", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Only your parents can turn this off", substring = true).assertExists()
        compose.onNodeWithText("Turn on notification access").performScrollTo().performClick()
        assertEquals(1, notificationTaps)
        compose.onNodeWithText("Turn off", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Stop", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Disable", substring = true).assertDoesNotExist()

        compose.runOnIdle { ui.value = ui.value.copy(access = AlertAccess(true, true)) }
        compose.onNodeWithText("Harbor is watching.").performScrollTo().assertIsDisplayed()
    }

    @Test fun parentListReadsAsPossibleConcernsAndSelfHarmShowsCrisisResources() {
        val view = mutableStateOf(AlertsViewState(
            AlertsViewStatus.AVAILABLE, notificationAccess = true, smsPermission = true,
            alerts = listOf(alert(AlertCategory.SELF_HARM, "2026-10-10T17:59:00Z"), alert(AlertCategory.BULLYING, "2026-10-10T17:50:00Z")),
            receivedAtMs = System.currentTimeMillis(),
        ))
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { ParentAlertsScreen(view.value) } } }
        compose.onNodeWithText("Possible self-harm or wellbeing concern", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Possible bullying or harassment", substring = true).assertExists()
        compose.onNodeWithText("988", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("never claims a message is safe", substring = true).performScrollTo().assertIsDisplayed()

        compose.runOnIdle { view.value = AlertsViewState(AlertsViewStatus.ACCESS_LOST) }
        compose.onNodeWithText("You no longer have access to this device's alerts.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Possible self-harm or wellbeing concern", substring = true).assertDoesNotExist()
    }

    @Test fun neverReportedShowsNoFalseConcern() {
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { ParentAlertsScreen(AlertsViewState(AlertsViewStatus.NONE)) } } }
        compose.onNodeWithText("No concerns have been flagged on this phone", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Possible", substring = true).assertDoesNotExist()
    }
}
