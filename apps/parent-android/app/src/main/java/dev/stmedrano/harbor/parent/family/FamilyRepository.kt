package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

enum class FamilyFailure { ACCESS_DENIED, NETWORK }
data class FamilyState(val snapshot: FamilySnapshot? = null, val loading: Boolean = false, val cached: Boolean = false, val failure: FamilyFailure? = null)

class FamilyRepository(private val api: ParentApi, private val cache: FamilyCacheDao, private val currentIdentity: () -> ParentIdentity?) {
    private val mutableState = MutableStateFlow(FamilyState())
    val state = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }
    private val generation = AtomicLong(0)
    private val commits = Mutex()
    private val frames = Any()

    suspend fun refresh(identity: ParentIdentity, familyId: String): FamilySnapshot {
        requireCurrent(identity)
        val ticket = synchronized(frames) { generation.incrementAndGet() }
        val previous = cached(identity, familyId)
        synchronized(frames) {
            requireTicket(identity, ticket)
            mutableState.value = FamilyState(previous, loading = true, cached = previous != null)
        }
        try {
            val snapshot = api.readFamily(familyId)
            requireTicket(identity, ticket)
            validate(snapshot, identity, familyId)
            commits.withLock {
                requireTicket(identity, ticket)
                cache.putSnapshot(FamilyCacheRow(identity.userId, familyId, json.encodeToString(snapshot), snapshot.fetchedAt))
                synchronized(frames) { requireTicket(identity, ticket); mutableState.value = FamilyState(snapshot) }
            }
            return snapshot
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: FamilyAccessDenied) {
            commits.withLock {
                if (current(identity, ticket)) {
                    cache.deleteFamily(identity.userId, familyId)
                    synchronized(frames) { if (current(identity, ticket)) mutableState.value = FamilyState(failure = FamilyFailure.ACCESS_DENIED) }
                }
            }
            throw denied
        } catch (failure: Exception) {
            synchronized(frames) { if (current(identity, ticket)) mutableState.value = FamilyState(previous, cached = previous != null, failure = FamilyFailure.NETWORK) }
            throw failure
        }
    }

    suspend fun cached(identity: ParentIdentity, familyId: String): FamilySnapshot? {
        if (currentIdentity() != identity) return null
        val row = cache.getSnapshot(identity.userId, familyId) ?: return null
        return try {
            json.decodeFromString<FamilySnapshot>(row.snapshotJson).also { validate(it, identity, familyId) }
        } catch (_: Exception) { cache.deleteFamily(identity.userId, familyId); null }
    }

    suspend fun clearFamily(identity: ParentIdentity, familyId: String) {
        val ticket = synchronized(frames) { generation.incrementAndGet() }
        commits.withLock {
            cache.deleteFamily(identity.userId, familyId)
            synchronized(frames) { if (generation.get() == ticket && mutableState.value.snapshot?.family?.id == familyId) mutableState.value = FamilyState() }
        }
    }
    fun hideVisible() = synchronized(frames) { generation.incrementAndGet(); mutableState.value = FamilyState() }
    suspend fun clearAll() { hideVisible(); commits.withLock { cache.clearAll() } }
    suspend fun retainUser(identity: ParentIdentity) {
        requireCurrent(identity)
        hideVisible()
        commits.withLock { requireCurrent(identity); cache.retainUser(identity.userId); requireCurrent(identity) }
    }
    suspend fun showCached(identity: ParentIdentity, familyId: String) {
        requireCurrent(identity)
        val ticket = synchronized(frames) { generation.incrementAndGet() }
        val snapshot = cached(identity, familyId)
        synchronized(frames) { requireTicket(identity, ticket); mutableState.value = FamilyState(snapshot, cached = true, failure = FamilyFailure.NETWORK) }
    }
    private fun current(identity: ParentIdentity, ticket: Long) = generation.get() == ticket && currentIdentity() == identity
    private fun requireTicket(identity: ParentIdentity, ticket: Long) { if (!current(identity, ticket)) throw AuthSessionRejected() }
    private fun requireCurrent(identity: ParentIdentity) { if (currentIdentity() != identity) throw AuthSessionRejected() }
    private fun validate(snapshot: FamilySnapshot, identity: ParentIdentity, familyId: String) {
        if (snapshot.family.version != 1 || snapshot.family.id != familyId || snapshot.membership.version != 1 ||
            snapshot.membership.familyId != familyId || snapshot.membership.userId != identity.userId ||
            snapshot.membership.status != "active" || snapshot.membership.role !in setOf("owner", "parent") ||
            snapshot.children.any { it.version != 1 || it.familyId != familyId } ||
            snapshot.devices.any { it.version != 1 || it.familyId != familyId || it.childId !in snapshot.children.map { child -> child.id } }) throw FamilyAccessDenied()
    }
}
