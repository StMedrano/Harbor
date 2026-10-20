package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.legal.LegalDoc
import dev.stmedrano.harbor.parent.location.LocationViewState
import dev.stmedrano.harbor.parent.location.LocationViewStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic names only. The parent app has four pages and a child switcher, like the web app. */
class ParentTabsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val stamp = "2026-01-01T00:00:00Z"
    private val kids = listOf(ChildV1(1, "a", "family", "Sam", stamp, stamp), ChildV1(1, "b", "family", "Jo", stamp, stamp))
    private fun phone(status: String = "active") = DevicePublicV1(1, "phone", "family", "a", "Phone", "standard", status, null, stamp, stamp)

    @Test fun bottomBarHasTheFourPagesAndSwitchingMarksOne() {
        compose.setContent { HarborTheme {
            var tab by remember { mutableStateOf(ParentTab.MAP) }
            HarborBottomBar(parentNavItems(tab) { tab = it })
        } }
        compose.onNodeWithText("Map").assertIsSelected()
        compose.onNodeWithText("Alerts").performClick()
        compose.onNodeWithText("Alerts").assertIsSelected()
        compose.onNodeWithText("Map").assertIsNotSelected()
        compose.onNodeWithText("Controls").assertDoesNotExist()
        compose.onNodeWithText("Settings").assertDoesNotExist()
    }

    @Test fun childChipsSelectAndAddFromEveryPage() {
        var picked = "a"
        var added = 0
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ChildChips(kids, picked, { picked = it }, { added++ })
        } } }
        compose.onNode(hasText("Sam") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Jo") and isSelectable()).performClick()
        assertEquals("b", picked)
        compose.onNodeWithText("+ Add child").performClick()
        assertEquals(1, added)
    }

    @Test fun pageWithoutAPhoneSaysSoAndOffersPairing() {
        var paired = 0
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ParentChildPage(ParentTab.MAP, kids[0], null, ParentChildData(), Color.Unspecified) { paired++ }
        } } }
        compose.onNodeWithText("Map").assertExists()
        compose.onNodeWithText("No phone yet").assertExists()
        compose.onNodeWithText("Pair device").performClick()
        assertEquals(1, paired)
    }

    @Test fun mapPageShowsOnlyLocationAndAlertsPageDoesNot() {
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ParentChildPage(ParentTab.MAP, kids[0], phone(), ParentChildData(location = LocationViewState(LocationViewStatus.NONE)), Color.Unspecified, null)
        } } }
        compose.onNodeWithText("Location").assertExists()
        compose.onNodeWithText("Message alerts").assertDoesNotExist()
    }

    @Test fun familyPageLinksToSettingsSecurityAboutAndPolicies() {
        val snapshot = FamilySnapshot(FamilyV1(1, "family", "Our family", "UTC", stamp, stamp),
            FamilyMemberV1(1, "m", "family", "parent", "owner", "active", stamp, stamp), kids, listOf(phone()), 1)
        val opened = mutableListOf<String>()
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            FamilyScreen(FamilyState(snapshot), listOf(snapshot.family), "a", {}, {}, {}, {}, {}, {}, {},
                onSettings = { opened += "settings" }, onSecurity = { opened += "security" }, onOpenDoc = { opened += it.name })
        } } }
        compose.onNodeWithText("Settings").performScrollTo().performClick()
        compose.onNodeWithText("Security").performScrollTo().performClick()
        compose.onNodeWithText("About Harbor").performScrollTo().performClick()
        compose.onNodeWithText("Privacy policy").performScrollTo().performClick()
        compose.onNodeWithText("Terms of service").performScrollTo().performClick()
        assertEquals(listOf("settings", "security", LegalDoc.PARENT_ABOUT.name, LegalDoc.PRIVACY.name, LegalDoc.TERMS.name), opened)
    }
}
