package dev.stmedrano.harbor.parent.profile

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.ui.*
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ProfileRetryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun blockedNetworkShowsRetryWithoutGrantingMenu() = runBlocking {
        val online = AtomicBoolean()
        val store = object : ProfileStore {
            override suspend fun read() = ProfileRole.PARENT
            override suspend fun write(role: ProfileRole) { assertEquals(ProfileRole.PARENT, role) }
            override suspend fun clear() = error("Retry must not erase records")
        }
        val coordinator = ProfileCoordinator(store, { if (!online.get()) throw IOException("offline"); "verified-parent" }, { null }, {}, {})
        coordinator.restore()
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            val state = coordinator.state.collectAsState().value
            val scope = rememberCoroutineScope()
            FamilyRoleContent(FamilyEntryState(state, parentIdentity = ParentIdentity("verified-parent", "session"), authenticationOpen = false),
                entry = { Text("Choose role") }, parentAuth = { Text("Parent login") }, parentMenu = { Text("Parent controls") },
                parentContent = { Text("Verified parent home") }, childPairing = { Text("Child pairing") }, childContent = { Text("Child home") },
                onRetry = { online.set(true); scope.launch { coordinator.retryValidation() } })
        } } }
        compose.onNodeWithText("Connection unavailable. Connect and retry your saved setup.").assertExists()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
        compose.onNodeWithText("Child pairing").assertDoesNotExist()
        compose.onNodeWithText("Retry saved setup").performScrollTo().performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Verified parent home").fetchSemanticsNodes().size == 1 }
        assertEquals("verified-parent", coordinator.currentLease()?.ownerId)
        compose.onNodeWithText("Parent controls").assertExists()
        Unit
    }
}
