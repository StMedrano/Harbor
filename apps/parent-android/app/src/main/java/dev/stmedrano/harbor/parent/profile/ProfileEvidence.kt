package dev.stmedrano.harbor.parent.profile

class ProfileValidationFailure(val reason: ProfileBlock) : IllegalStateException()
class ProfileEvidence(private val parentRecords: () -> Boolean, private val childRecords: () -> Boolean) {
    fun assertSingleProfile() {
        if (parentRecords() && childRecords()) throw ProfileValidationFailure(ProfileBlock.AMBIGUOUS)
    }
}
