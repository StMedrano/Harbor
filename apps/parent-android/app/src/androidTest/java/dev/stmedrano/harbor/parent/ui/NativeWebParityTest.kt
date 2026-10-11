package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import org.junit.Rule
import org.junit.Test

/** Native navigation and unavailable-state contract, checked on API 29 and 36 without live credentials. */
class NativeWebParityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
