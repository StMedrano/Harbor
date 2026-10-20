package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.stmedrano.harbor.parent.location.LocationAccess
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ChildSetupGateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val none = PermissionSnapshot(LocationAccess(false, false, true), notificationsOn = false, usageGranted = false)
    /** The real app scrolls the gate inside its page; the test host needs the same so a small screen can still reach the last button. */
    @Composable private fun Scrolling(content: @Composable () -> Unit) = Box(Modifier.verticalScroll(rememberScrollState())) { content() }
    private val all = PermissionSnapshot(LocationAccess(true, true, true), notificationsOn = true, usageGranted = true)

    @Test fun incompleteSetupListsEveryReasonAndCannotBeFinished() {
        val asked = mutableListOf<SetupStepId>()
        compose.setContent { HarborTheme { Scrolling { ChildSetupGate(childSetupSteps(none), { asked += it }, {}, {}) } } }
        compose.onNodeWithText("Set up this phone").assertExists()
        compose.onNodeWithText("0 of 4").assertExists()
        compose.onNodeWithText("Measure screen time. Never blocks apps.").assertExists()
        compose.onNodeWithText("Finish setup").assertIsNotEnabled()
        compose.onNodeWithText("Turn on everything above to continue.").assertExists()
        compose.onNodeWithContentDescription("Turn on Location").performClick()
        compose.onNodeWithContentDescription("Turn on Notifications").performClick()
        assertEquals(listOf(SetupStepId.LOCATION, SetupStepId.NOTIFICATIONS), asked)
    }

    @Test fun finishingNeedsEveryPermissionAndIsADeliberateTap() {
        var finished = 0
        compose.setContent { HarborTheme { Scrolling { ChildSetupGate(childSetupSteps(all), {}, {}, { finished++ }) } } }
        compose.onNodeWithText("4 of 4").assertExists()
        compose.onNodeWithText("Finish setup").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1, finished)
    }

    @Test fun onlyMissingPermissionsOfferTurnOn() {
        compose.setContent { HarborTheme { Scrolling { ChildSetupGate(childSetupSteps(all.copy(usageGranted = false)), {}, {}, {}) } } }
        compose.onNodeWithContentDescription("Turn on Usage access").assertExists()
        compose.onNodeWithContentDescription("Turn on Location").assertDoesNotExist()
        compose.onNodeWithText("3 of 4").assertExists()
    }
}
