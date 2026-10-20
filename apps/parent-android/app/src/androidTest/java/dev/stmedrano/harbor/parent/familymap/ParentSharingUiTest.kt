package dev.stmedrano.harbor.parent.familymap

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stmedrano.harbor.parent.ui.HarborTheme
import dev.stmedrano.harbor.parent.ui.ParentSharingPanel
import dev.stmedrano.harbor.parent.ui.ParentSharingUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ParentSharingUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val family = "33333333-3333-4333-8333-333333333333"
    private fun show(state: ParentFamilyMapState, label: String = "Parent", placeProblem: String? = null,
        onShare: (Boolean) -> Unit = {}, onChildren: (Boolean) -> Unit = {}, onRetry: () -> Unit = {}) =
        compose.setContent { HarborTheme { Box(Modifier.verticalScroll(rememberScrollState())) {
            ParentSharingPanel(ParentSharingUi(state, label, {}, placeProblem, onShare, onChildren, onRetry)) } } }

    @Test fun bothSwitchesAreLabelledAndReportTheirNewValue() {
        val shared = mutableListOf<Boolean>(); val children = mutableListOf<Boolean>()
        show(ParentFamilyMapState(family, loaded = true), onShare = { shared += it }, onChildren = { children += it })
        compose.onNodeWithText("Share my location").assertExists()
        compose.onNodeWithText("Children can see the family map").assertExists()
        compose.onNodeWithContentDescription("Share my location").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Children can see the family map").performScrollTo().performClick()
        assertEquals(listOf(true), shared)
        assertEquals(listOf(false), children)
    }
    @Test fun whileSharingTheNameIsShownAndTheSwitchTurnsItOff() {
        val shared = mutableListOf<Boolean>()
        show(ParentFamilyMapState(family, loaded = true, mySharing = MySharing("Alex", "2026-10-20T15:58:00Z")), "Alex", onShare = { shared += it })
        compose.onNodeWithText("Sharing as Alex").assertExists()
        compose.onNodeWithText("Name on the map").assertDoesNotExist()
        compose.onNodeWithContentDescription("Share my location").performScrollTo().performClick()
        assertEquals(listOf(false), shared)
    }
    @Test fun anInvalidNameCannotStartSharing() {
        show(ParentFamilyMapState(family, loaded = true), label = "")
        compose.onNodeWithText("Use 1 to 40 characters.").assertExists()
        compose.onNodeWithContentDescription("Share my location").assertIsNotEnabled()
    }
    @Test fun switchesAreLockedWhileASaveIsInFlight() {
        show(ParentFamilyMapState(family, loaded = true, busy = true))
        compose.onNodeWithContentDescription("Children can see the family map").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Share my location").assertIsNotEnabled()
    }
    @Test fun loadingAndFailureAreHonest() {
        show(ParentFamilyMapState(family, loaded = false, problem = FamilyMapProblem.COULD_NOT_LOAD))
        compose.onNodeWithText("Could not load the map settings.").assertExists()
        compose.onNodeWithContentDescription("Share my location").assertDoesNotExist()
    }
    @Test fun problemsAndPlaceHintsAreSaidPlainly() {
        show(ParentFamilyMapState(family, loaded = true, problem = FamilyMapProblem.COULD_NOT_SAVE), placeProblem = "Turn on location in your phone's settings to share.")
        compose.onNodeWithText("Could not save that change. Try again.").assertExists()
        compose.onNodeWithText("Turn on location in your phone's settings to share.").assertExists()
        compose.onNodeWithContentDescription("Share my location").assertIsEnabled()
    }
}
