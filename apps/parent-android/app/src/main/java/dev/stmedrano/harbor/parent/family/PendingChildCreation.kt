package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.FamilyCacheDao
import dev.stmedrano.harbor.parent.data.PendingChildRow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.UUID

class PendingChildCreation(private val api: ParentApi, private val cache: FamilyCacheDao,
    private val currentIdentity: () -> ParentIdentity?, private val newKey: () -> String = { UUID.randomUUID().toString() }) {
    private val mutex = Mutex()
    suspend fun submit(identity: ParentIdentity, familyId: String, displayName: String): ChildV1 = mutex.withLock {
        requireCurrent(identity)
        val name = displayName.trim()
        require(name.codePointCount(0, name.length) in 1..100 && name.none(Char::isISOControl)) { "Enter a child name" }
        val payload = Json.encodeToString(listOf(familyId, name))
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }
        val pending = cache.beginPending(PendingChildRow(identity.userId, familyId, newKey(), fingerprint, name))
        if (pending.fingerprint != fingerprint) throw PendingChildConflict()
        val result = api.createChild(CreateChildRequest(familyId, pending.displayName, pending.idempotencyKey))
        requireCurrent(identity)
        require(result.version == 1 && result.familyId == familyId && result.displayName == name) { "Child response rejected" }
        cache.deletePending(identity.userId, familyId, pending.idempotencyKey)
        result
    }
    suspend fun cancel(identity: ParentIdentity, familyId: String) = mutex.withLock {
        requireCurrent(identity)
        cache.getPending(identity.userId, familyId)?.let { cache.deletePending(identity.userId, familyId, it.idempotencyKey) }
    }
    private fun requireCurrent(identity: ParentIdentity) { if (currentIdentity() != identity) throw AuthSessionRejected() }
}
