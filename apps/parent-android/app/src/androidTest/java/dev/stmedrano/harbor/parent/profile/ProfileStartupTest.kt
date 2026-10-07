package dev.stmedrano.harbor.parent.profile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import dev.stmedrano.harbor.parent.MainActivity
import dev.stmedrano.harbor.parent.ParentApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileStartupTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun offlineFixtureBootstrapsWithoutProtectedRuntime() {
        val graph = compose.activity.application as ParentApplication
        compose.waitUntil(5000) { graph.profiles.state.value != ProfileState.Transitioning }
        assertEquals(ProfileState.Setup, graph.profiles.state.value)
        assertNull(graph.authRepository)
        compose.onNodeWithText("Parent").assertExists()
        compose.onNodeWithText("Child").assertExists()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Security").assertDoesNotExist()
        compose.onNodeWithText("Child").performClick()
        compose.onNodeWithText("Enter pairing code").assertExists()
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Security").assertDoesNotExist()
        assertNull(graph.authRepository)
    }
}
