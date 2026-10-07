package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.ParentIdentity

class ParentBackgroundProcessor(private val currentIdentity: () -> ParentIdentity?, private val optedOwner: () -> ParentIdentity?,
    private val restore: suspend () -> Unit, private val bind: suspend () -> Unit,
    private val syncCurrentToken: suspend (ParentIdentity) -> Unit, private val message: suspend (Map<String, String>) -> Boolean) {
    suspend fun process(owner: ParentIdentity, data: Map<String, String>?): Boolean {
        if (optedOwner() != owner || (data != null && ParentMessageParser.parse(data) == null)) return false
        if (currentIdentity() == null) restore()
        if (currentIdentity() != owner || optedOwner() != owner) return false
        bind()
        if (currentIdentity() != owner || optedOwner() != owner) return false
        if (data != null) return message(data)
        syncCurrentToken(owner)
        return true // Work handled, not a claim of backend registration or delivery.
    }
}
