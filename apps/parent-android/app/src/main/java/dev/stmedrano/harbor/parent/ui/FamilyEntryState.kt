package dev.stmedrano.harbor.parent.ui

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.profile.ProfileRole
import dev.stmedrano.harbor.parent.profile.ProfileState

enum class FamilyDestination { ENTRY, PARENT_AUTH, PARENT_HOME, CHILD_PAIRING, CHILD_HOME, BLOCKED, RESTORING }
data class FamilyEntryState(val profile: ProfileState, val setupRole: ProfileRole? = null,
    val parentIdentity: ParentIdentity? = null, val authenticationOpen: Boolean = true,
    val childBinding: ChildBinding? = null, val childState: ChildSyncState? = null) {
    fun destination(): FamilyDestination = when (val active = profile) {
        ProfileState.Transitioning -> FamilyDestination.RESTORING
        is ProfileState.Blocked -> FamilyDestination.BLOCKED
        ProfileState.Setup -> when {
            childBinding != null || childState is ChildSyncState.Blocked -> FamilyDestination.BLOCKED
            setupRole == ProfileRole.PARENT -> FamilyDestination.PARENT_AUTH
            setupRole == ProfileRole.CHILD -> FamilyDestination.CHILD_PAIRING
            else -> FamilyDestination.ENTRY
        }
        is ProfileState.Parent -> if (parentIdentity?.userId == active.lease.ownerId && !authenticationOpen)
            FamilyDestination.PARENT_HOME else FamilyDestination.PARENT_AUTH
        is ProfileState.Child -> if (childBinding?.deviceId == active.lease.ownerId && childState != null && childState !is ChildSyncState.Blocked)
            FamilyDestination.CHILD_HOME else FamilyDestination.BLOCKED
    }
}
