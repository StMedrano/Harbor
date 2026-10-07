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
}
