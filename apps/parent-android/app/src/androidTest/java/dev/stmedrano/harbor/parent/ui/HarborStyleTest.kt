package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HarborStyleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun childAndParentChoicesReportTheirTaps() {
        var parent = 0
        var child = 0
        compose.setContent { HarborTheme { FamilyEntryScreen(onParent = { parent++ }, onChild = { child++ }) } }
        compose.onNodeWithText("Child").performClick()
        compose.onNodeWithText("Parent").performClick()
        assertEquals(1, child)
        assertEquals(1, parent)
    }

    @Test fun tabsShowAndMoveTheSelectedTab() {
        compose.setContent {
            HarborTheme {
                var selected by remember { mutableStateOf("Family") }
                HarborTabs(listOf("Family", "Settings", "Security").map { label ->
                    HarborTab(label, selected == label) { selected = label }
                })
                Text("Showing $selected")
            }
        }
        compose.onNodeWithText("Family").assertIsSelected()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Settings").assertIsSelected()
        compose.onNodeWithText("Showing Settings").assertExists()
    }

    @Test fun bottomBarShowsAndMovesTheSelectedDestination() {
        compose.setContent {
            HarborTheme {
                var selected by remember { mutableStateOf("Family") }
                HarborBottomBar(listOf("Family" to HarborGlyph.FAMILY, "Settings" to HarborGlyph.SETTINGS, "Security" to HarborGlyph.SECURITY)
                    .map { (label, glyph) -> HarborNavItem(label, glyph, selected == label) { selected = label } })
                Text("Showing $selected")
            }
        }
        compose.onNodeWithText("Family").assertIsSelected()
        compose.onNodeWithText("Security").performClick()
        compose.onNodeWithText("Security").assertIsSelected()
        compose.onNodeWithText("Showing Security").assertExists()
    }
}
