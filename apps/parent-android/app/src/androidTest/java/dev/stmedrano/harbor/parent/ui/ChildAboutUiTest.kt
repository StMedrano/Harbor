package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stmedrano.harbor.parent.alerts.MonitoredApps
import dev.stmedrano.harbor.parent.legal.LegalDoc
import org.junit.Rule
import org.junit.Test

/** The About and policy pages are files shipped with the app; these tests read the real files, with no network. */
class ChildAboutUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun childAboutShowsHeadingsAndFilledAppList() {
        compose.setContent { HarborTheme { Box(Modifier.verticalScroll(rememberScrollState())) { LegalBody(rememberLegalBlocks(LegalDoc.CHILD_ABOUT)) } } }
        compose.onNodeWithText("What Harbor shares with your parents").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText("{{", substring = true).assertCountEquals(0)
        compose.onNodeWithText(MonitoredApps.labels().first(), substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun policyLinksOpenBothDocumentsAndBackReturns() {
        compose.setContent {
            HarborTheme {
                var open by remember { mutableStateOf<LegalDoc?>(null) }
                val doc = open
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    if (doc == null) PolicyLinks { open = it } else LegalScreen(doc) { open = null }
                }
            }
        }
        compose.onNodeWithText("Privacy policy").performClick()
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onAllNodesWithText("Terms of service").assertCountEquals(0)
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Terms of service").performClick()
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Privacy policy").assertIsDisplayed()
    }

    @Test fun everyDocumentLoadsWithoutPlaceholders() {
        var doc by mutableStateOf(LegalDoc.CHILD_ABOUT)
        compose.setContent { HarborTheme { Box(Modifier.verticalScroll(rememberScrollState())) { LegalBody(rememberLegalBlocks(doc)) } } }
        for (d in LegalDoc.values()) {
            compose.runOnIdle { doc = d }
            compose.onAllNodesWithText("{{", substring = true).assertCountEquals(0)
            compose.onAllNodesWithText("could not be loaded", substring = true).assertCountEquals(0)
        }
    }

    @Test fun statusLineHasTextAndOptionalDetail() {
        compose.setContent { HarborTheme { HarborStatus("Sharing location", ok = true, detail = "Last sent 2 min ago") } }
        compose.onNodeWithText("Sharing location").assertIsDisplayed()
        compose.onNodeWithText("Last sent 2 min ago").assertIsDisplayed()
    }
}
