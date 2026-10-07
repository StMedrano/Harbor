package dev.stmedrano.harbor.parent.ui

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildFailure
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.profile.*
import org.junit.Assert.*
import org.junit.Test

class FamilyEntryStateTest {
    private val parent = ProfileState.Parent(ProfileLease(ProfileRole.PARENT, "parent-a", 1))
    private val binding = ChildBinding("device-a", "family-a", "child-a")
    private val child = ProfileState.Child(ProfileLease(ProfileRole.CHILD, "device-a", 1))
    @Test fun roleSelectionAloneCannotOpenDashboard() {
        assertEquals(FamilyDestination.PARENT_AUTH, FamilyEntryState(ProfileState.Setup, setupRole = ProfileRole.PARENT).destination())
        assertEquals(FamilyDestination.CHILD_PAIRING, FamilyEntryState(ProfileState.Setup, setupRole = ProfileRole.CHILD).destination())
        assertEquals(FamilyDestination.ENTRY, FamilyEntryState(ProfileState.Setup).destination())
    }
    @Test fun verifiedParentOpensOnlyItsOwnDashboard() {
        assertEquals(FamilyDestination.PARENT_HOME, FamilyEntryState(parent, parentIdentity = ParentIdentity("parent-a", "session-a"), authenticationOpen = false).destination())
        assertEquals(FamilyDestination.PARENT_AUTH, FamilyEntryState(parent, parentIdentity = ParentIdentity("parent-b", "session-b"), authenticationOpen = false).destination())
    }
    @Test fun recoveryKeepsParentMenuClosed() {
        assertEquals(FamilyDestination.PARENT_AUTH, FamilyEntryState(parent, parentIdentity = ParentIdentity("parent-a", "session-a"), authenticationOpen = true).destination())
    }
    @Test fun restoredChildRequiresMatchingConfirmedBinding() {
        assertEquals(FamilyDestination.CHILD_HOME, FamilyEntryState(child, childBinding = binding, childState = ChildSyncState.Stale(null)).destination())
        assertEquals(FamilyDestination.BLOCKED, FamilyEntryState(child).destination())
        assertEquals(FamilyDestination.BLOCKED, FamilyEntryState(child, childBinding = binding.copy(deviceId = "other-device"), childState = ChildSyncState.Stale(null)).destination())
    }
    @Test fun revokedChildCannotOpenParentEvenWithParentIdentity() {
        val value = FamilyEntryState(child, parentIdentity = ParentIdentity("parent-a", "session-a"), authenticationOpen = false,
            childBinding = binding, childState = ChildSyncState.Blocked(ChildFailure.REVOKED))
        assertEquals(FamilyDestination.BLOCKED, value.destination())
    }
    @Test fun invalidAndRestoringProfilesNeverExposeMenus() {
        assertEquals(FamilyDestination.BLOCKED, FamilyEntryState(ProfileState.Blocked(ProfileBlock.AMBIGUOUS)).destination())
        assertEquals(FamilyDestination.RESTORING, FamilyEntryState(ProfileState.Transitioning).destination())
    }
}
