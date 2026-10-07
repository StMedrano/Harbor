package dev.stmedrano.harbor.parent

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyViewModel
import dev.stmedrano.harbor.parent.notifications.ParentRegistrationStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// The same startup path is used after encrypted restore, foreground and background work.
class ParentContextBinding(private val currentIdentity: () -> ParentIdentity?, private val family: FamilyViewModel,
    private val registration: ParentRegistrationStore, private val hide: () -> Unit, private val enable: suspend () -> Unit) {
    private val changes = Mutex()
    private var bound: ParentIdentity? = null
    fun reset() { bound = null }
    suspend fun ensure() = changes.withLock {
        val identity = currentIdentity() ?: return@withLock
        if (bound == identity) return@withLock
        hide()
        check(currentIdentity() == identity)
        family.retainUser(identity)
        family.load(identity)
        check(currentIdentity() == identity)
        if (registration.optedIn(identity)) enable()
        check(currentIdentity() == identity)
        bound = identity
    }
}
