package dev.stmedrano.harbor.parent.familymap

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.stmedrano.harbor.parent.location.locationInstantString
import dev.stmedrano.harbor.parent.ui.ChildFamilyMapUi
import dev.stmedrano.harbor.parent.ui.FamilyMapScreen
import dev.stmedrano.harbor.parent.ui.HarborTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FamilyMapUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun at(minutesAgo: Long) = locationInstantString(System.currentTimeMillis() - minutesAgo * 60_000L)
    private fun fix(minutesAgo: Long) = FamilyMapFix(at(minutesAgo), 41.5, -87.6, 12)
    private val people = listOf(
        FamilyMapPerson("5b8d2c40-7e19-4a63-9f05-1c3e6a8b2d74", PersonKind.CHILD, "Maya", true, fix(2)),
        FamilyMapPerson("6c9e3d51-8f2a-4b74-8a16-2d4f7b9c3e85", PersonKind.CHILD, "Jo", false, null),
        FamilyMapPerson("7d0f4e62-9a3b-4c85-9b27-3e5a8c0d4f96", PersonKind.PARENT, "Alex", false, fix(5)),
    )
    private fun show(state: FamilyMapUiState, onRefresh: () -> Unit = {}) =
        compose.setContent { HarborTheme { Box(Modifier.verticalScroll(rememberScrollState())) { FamilyMapScreen(ChildFamilyMapUi(state, onRefresh)) } } }

    @Test fun mapListsEveryoneWithZoomControls() {
        var refreshed = 0
        show(FamilyMapUiState(loaded = true, enabled = true, people = people)) { refreshed++ }
        compose.onNodeWithText("Maya (you)").assertExists()
        compose.onNodeWithText("Jo").assertExists()
        compose.onNodeWithText("No place yet").assertExists()
        compose.onNodeWithText("Alex").assertExists()
        compose.onNodeWithText("Parent").assertExists()
        compose.onNodeWithContentDescription("Zoom in").assertExists()
        compose.onNodeWithContentDescription("Zoom out").assertExists()
        compose.onNodeWithText("Refresh").performScrollTo().performClick()
        assertEquals(1, refreshed)
    }
    @Test fun aTurnedOffMapSaysSoAndShowsNobody() {
        show(FamilyMapUiState(loaded = true, enabled = false))
        compose.onNodeWithText("A parent turned the family map off.").assertExists()
        compose.onNodeWithContentDescription("Zoom in").assertDoesNotExist()
    }
    @Test fun nobodyWithAPlaceSaysNoOneHasSharedYet() {
        show(FamilyMapUiState(loaded = true, enabled = true, people = people.filter { it.fix == null }))
        compose.onNodeWithText("No one has shared a place yet.").assertExists()
        compose.onNodeWithContentDescription("Zoom in").assertDoesNotExist()
    }
    @Test fun loadingAndOfflineAreHonest() {
        show(FamilyMapUiState())
        compose.onNodeWithText("Loading…").assertExists()
    }
    @Test fun failureBeforeAnyAnswerNeverClaimsAnEmptyFamily() {
        show(FamilyMapUiState(offline = true))
        compose.onNodeWithText("Could not load the family map. Check your connection.").assertExists()
        compose.onNodeWithText("No one has shared a place yet.").assertDoesNotExist()
    }
    @Test fun staleAnswerKeepsTheMapWithANote() {
        show(FamilyMapUiState(loaded = true, enabled = true, people = people, offline = true))
        compose.onNodeWithText("Showing the last map that loaded. It could not be refreshed just now.").assertExists()
        compose.onNodeWithText("Maya (you)").assertExists()
    }
}
