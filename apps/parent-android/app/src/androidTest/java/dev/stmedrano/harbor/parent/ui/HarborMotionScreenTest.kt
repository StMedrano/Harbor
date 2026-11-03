package dev.stmedrano.harbor.parent.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

/** Pages arrive and the dock's tint moves, and everything ends up in place. Synthetic labels only. */
class HarborMotionScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aTabChangeBringsThePageInAndSlidesTheTintAcross() {
        var tab by mutableStateOf("One")
        rule.setContent {
            HarborTheme {
                ParentApp(bottomBar = {
                    HarborBottomBar(listOf("One", "Two", "Three", "Four").map { name ->
                        HarborNavItem(name, HarborGlyph.TODAY, tab == name) { tab = name }
                    })
                }) {
                    val page = listOf("One", "Two", "Three", "Four").indexOf(tab)
                    androidx.compose.foundation.layout.Column(rememberHarborArrival(tab, page, playFirst = false)) {
                        HarborCard(title = "Page $tab") { HarborMeter(0.6f); Text("Synthetic content") }
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Page One").assertIsDisplayed()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Four").performClick()
        rule.mainClock.advanceTimeBy(80)
        rule.mainClock.advanceTimeBy(80)
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        rule.onNodeWithText("Page Four").assertIsDisplayed()
    }
}
