package dev.stmedrano.harbor.parent.profile

enum class ProfileRole { PARENT, CHILD }
data class ProfileLease(val role: ProfileRole, val ownerId: String, val generation: Long)
enum class ProfileBlock { AMBIGUOUS, INVALID_CREDENTIALS, STORAGE_UNAVAILABLE }
sealed interface ProfileState {
    data object Setup : ProfileState
    data class Parent(val lease: ProfileLease) : ProfileState
    data class Child(val lease: ProfileLease) : ProfileState
    data class Blocked(val reason: ProfileBlock) : ProfileState
    data object Transitioning : ProfileState
}
