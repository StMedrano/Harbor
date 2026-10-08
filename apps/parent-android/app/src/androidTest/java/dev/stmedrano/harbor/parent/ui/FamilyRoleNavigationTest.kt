package dev.stmedrano.harbor.parent.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.child.*
import dev.stmedrano.harbor.parent.profile.*
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.Bitmap
import java.io.File

class FamilyRoleNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val parent = ProfileState.Parent(ProfileLease(ProfileRole.PARENT, "parent-a", 1))
    private val binding = ChildBinding("device-a", "family-a", "child-a")
    private val child = ProfileState.Child(ProfileLease(ProfileRole.CHILD, "device-a", 1))
    private fun show(state: FamilyEntryState) {
        compose.setContent { HarborTheme { ParentApp {
            FamilyRoleContent(state,
                entry = { FamilyEntryScreen({}, {}) },
                parentAuth = { Text("Sign in or create account") },
                parentMenu = { Text("Parent controls") },
                parentContent = { Text("Parent dashboard") },
                childPairing = { Text("Enter pairing code") },
                childContent = { ChildDashboard(checkNotNull(state.childState), checkNotNull(state.childBinding), {}, {}) })
        } } }
    }
    @Test fun setupHasNoMenu() {
        show(FamilyEntryState(ProfileState.Setup))
        compose.onNodeWithText("Parent").assertExists()
        compose.onNodeWithText("Child").assertExists()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()
    }
    @Test fun parentLoginOpensOnlyParent() {
        show(FamilyEntryState(parent, parentIdentity = ParentIdentity("parent-a", "session-a"), authenticationOpen = false))
        compose.onNodeWithText("Parent controls").assertExists()
        compose.onNodeWithText("Parent dashboard").assertExists()
        compose.onNodeWithText("Enter pairing code").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()
    }
    @Test fun childCodeCannotOpenParent() {
        show(FamilyEntryState(ProfileState.Setup, setupRole = ProfileRole.CHILD))
        compose.onNodeWithText("Enter pairing code").assertExists()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()
    }
    @Test fun confirmedChildRestoresToday() {
        show(FamilyEntryState(child, childBinding = binding, childState = ChildSyncState.Stale(null)))
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Apps").assertExists()
        compose.onNodeWithText("About").assertExists()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
    }
    @Test fun recoveryBackDoesNotEscape() {
        show(FamilyEntryState(parent, parentIdentity = ParentIdentity("parent-a", "session-a"), authenticationOpen = true))
        compose.onNodeWithText("Sign in or create account").assertExists()
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
    }
    @Test fun revokedChildCannotOpenParent() {
        show(FamilyEntryState(child, parentIdentity = ParentIdentity("parent-a", "session-a"), authenticationOpen = false,
            childBinding = binding, childState = ChildSyncState.Blocked(ChildFailure.REVOKED)))
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
        compose.onNodeWithText("Today").assertDoesNotExist()
        compose.onNodeWithText("This phone needs a parent to check its setup.").assertExists()
    }
    @Test fun largeTextChildDashboardShowsHonestTabsInBothThemes() {
        val dark = mutableStateOf(false)
        compose.setContent { HarborTheme(dark = dark.value) { ParentApp(showBrand = false) {
            ChildDashboard(ChildSyncState.Stale(1000), binding, {}, {})
        } } }
        compose.onNodeWithText("Showing saved setup. Current status has not been confirmed.").performScrollTo().assertIsDisplayed()
        fun capture(name: String) {
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            File(compose.activity.getExternalFilesDir(null), name).outputStream().use {
                image.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        capture("family-child-light.png")
        compose.onNodeWithText("Apps").performScrollTo().performClick()
        compose.onNodeWithText("App information is not available yet.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("About").performScrollTo().performClick()
        compose.onNodeWithText("Screen time and app enforcement are not available in this build.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { dark.value = true }
        capture("family-child-dark.png")
        compose.onNodeWithText("Parent controls").assertDoesNotExist()
    }
}
