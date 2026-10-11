package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import java.io.File
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import org.junit.Rule
import org.junit.Test

/** Native navigation and unavailable-state contract, checked on API 29 and 36 without live credentials. */
class NativeWebParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun parentBottomNavigationMatchesTheFiveWebDestinations() {
        val selected = mutableStateOf("Map")
        compose.setContent { HarborTheme {
            ParentApp(bottomBar = { HarborBottomBar(parentWebNavItems(selected.value) { selected.value = it }) }) {
                androidx.compose.material3.Text("Native Harbor")
            }
        } }
        listOf("Map", "Alerts", "Controls", "Activity", "Family").forEach {
            compose.onNodeWithText(it).assertExists()
        }
        compose.onNodeWithText("Map").assertIsSelected()
        compose.onNodeWithText("Activity").performClick()
        compose.onNodeWithText("Activity").assertIsSelected()
        capture("native-web-parent-shell.png")
    }

    @Test fun childHomeUsesFriendlyWebGreetingAndTruthfulPairingStatus() {
        val binding = dev.stmedrano.harbor.parent.child.ChildBinding("device-a", "family-a", "child-a")
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ChildDashboard(dev.stmedrano.harbor.parent.child.ChildSyncState.Stale(null), binding, {}, {})
        } } }
        compose.onNodeWithText("Hi there").assertExists()
        compose.onNodeWithText("This phone is paired").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Showing saved setup. Current status has not been confirmed.")
            .performScrollTo().assertIsDisplayed()
        capture("native-web-child-today.png")
    }

    /**
     * High-font screenshots are intentionally captured against fixture-only state on each emulator:
     * they protect navigation/disclosure legibility without inventing backend results.
     * The runner switches Android's font_scale to 1.8 before invoking these methods.
     */
    @Test fun largeTextDarkParentShellRetainsAllFiveWebDestinations() {
        val selected = mutableStateOf("Map")
        compose.setContent { HarborTheme(dark = true) {
            ParentApp(bottomBar = { HarborBottomBar(parentWebNavItems(selected.value) { selected.value = it }) }) {
                androidx.compose.material3.Text("Native Harbor")
            }
        } }
        listOf("Map", "Alerts", "Controls", "Activity", "Family").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        compose.onNodeWithText("Family").performClick()
        compose.onNodeWithText("Family").assertIsSelected()
        capture("native-web-parent-shell-dark-large-text.png")
    }

    @Test fun largeTextDarkChildTodayMakesUnconfirmedStatusVisible() {
        val binding = dev.stmedrano.harbor.parent.child.ChildBinding("device-a", "family-a", "child-a")
        compose.setContent { HarborTheme(dark = true) {
            ParentApp(showBrand = false) {
                ChildDashboard(dev.stmedrano.harbor.parent.child.ChildSyncState.Stale(null), binding, {}, {})
            }
        } }
        compose.onNodeWithText("Hi there").assertExists()
        compose.onNodeWithText("Unconfirmed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Showing saved setup. Current status has not been confirmed.")
            .performScrollTo().assertIsDisplayed()
        capture("native-web-child-today-dark-large-text.png")
    }

    @Test fun unsupportedTabsDoNotPretendToMonitorOrBlock() {
        val identity = ParentIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "fixture")
        val selected = mutableStateOf("Alerts")
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ParentWebPage(selected.value, null, identity, null, null)
        } } }
        compose.onNodeWithText("Message alerts are not available in this Android build yet.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("All caught up", substring = true).assertDoesNotExist()
        compose.runOnIdle { selected.value = "Controls" }
        compose.onNodeWithText("App limits and phone pausing are not available yet.", substring = true)
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Pause phone now", substring = true).assertDoesNotExist()
    }
}
