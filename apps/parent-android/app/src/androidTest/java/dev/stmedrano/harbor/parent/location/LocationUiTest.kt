package dev.stmedrano.harbor.parent.location

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.ui.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic coordinates only. Fixture builds have no network access; tests never contact a tile host. */
class LocationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun childStatusIsShortAndNeverOffersAStopButton() {
        var permissionRequests = 0
        val ui = mutableStateOf(ChildLocationUi(LocationAccess(false, false, true), LocationHostState(),
            { permissionRequests++ }, {}, {}, {}))
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { HarborCard { LocationStatusSection(ui.value) } } } }
        compose.onNodeWithText("Location off").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Allow location").performScrollTo().performClick()
        assertEquals(1, permissionRequests)
        compose.onNodeWithText("Stop sharing", substring = true).assertDoesNotExist()

        compose.runOnIdle { ui.value = ui.value.copy(access = LocationAccess(true, false, true)) }
        compose.onNodeWithText("Allow all the time").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sharing only while Harbor is open").assertExists()
        compose.onNodeWithText("Sharing location").assertDoesNotExist()

        compose.runOnIdle { ui.value = ui.value.copy(access = LocationAccess(true, true, false)) }
        compose.onNodeWithText("Location is off in Android settings").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Sharing location").assertDoesNotExist()

        compose.runOnIdle { ui.value = ui.value.copy(access = LocationAccess(true, true, true)) }
        compose.onNodeWithText("Sharing location").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Turn off", substring = true).assertDoesNotExist()
    }

    @Test fun lastKnownMapIsMarkedStaleAndRevocationHidesItImmediately() {
        val view = mutableStateOf(LocationViewState(
            status = LocationViewStatus.AVAILABLE,
            deviceStatus = LocationStatus.SHARING,
            fixes = listOf(
                LocationFix("2026-10-10T17:59:00Z", 0.0, 179.95, 15, LocationProvider.GPS),
                LocationFix("2026-10-10T17:50:00Z", 0.0, -179.95, 22, LocationProvider.GPS)),
            receivedAtMs = System.currentTimeMillis() - 31L * 60_000L,
        ))
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { ParentLocationScreen(view.value) } } }
        compose.onNodeWithText("Nothing new has arrived from this phone", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Last known place", substring = true).assertExists()
        compose.runOnIdle { view.value = LocationViewState(LocationViewStatus.ACCESS_LOST) }
        compose.onNodeWithText("You no longer have access to this device's location.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Last known place", substring = true).assertDoesNotExist()
    }

    @Test fun neverReportedHasNoMapAndNoFalseSharingClaim() {
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ParentLocationScreen(LocationViewState(LocationViewStatus.NONE))
        } } }
        compose.onNodeWithText("This phone has not shared a place yet", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Last known place", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Sharing location").assertDoesNotExist()
    }
}
