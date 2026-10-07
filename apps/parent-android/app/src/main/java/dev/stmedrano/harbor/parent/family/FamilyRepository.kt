package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

enum class FamilyFailure { ACCESS_DENIED, NETWORK }
data class FamilyState(val snapshot: FamilySnapshot? = null, val loading: Boolean = false, val cached: Boolean = false, val failure: FamilyFailure? = null)

class FamilyRepository(private val api: ParentApi, private val cache: FamilyCacheDao, private val currentIdentity: () -> ParentIdentity?) {
    private val mutableState = MutableStateFlow(FamilyState())
    val state = mutableState.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun refresh(identity: ParentIdentity, familyId: String): FamilySnapshot {
        requireCurrent(identity)
        val previous = cached(identity, familyId)
        mutableState.value = FamilyState(previous, loading = true, cached = previous != null)
        try {
            val snapshot = api.readFamily(familyId)
            requireCurrent(identity)
            validate(snapshot, identity, familyId)
            cache.putSnapshot(FamilyCacheRow(identity.userId, familyId, json.encodeToString(snapshot), snapshot.fetchedAt))
            requireCurrent(identity)
            mutableState.value = FamilyState(snapshot)
            return snapshot
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: FamilyAccessDenied) {
            cache.deleteFamily(identity.userId, familyId)
            if (currentIdentity() == identity) mutableState.value = FamilyState(failure = FamilyFailure.ACCESS_DENIED)
            throw denied
        } catch (failure: Exception) {
            if (currentIdentity() == identity) mutableState.value = FamilyState(previous, cached = previous != null, failure = FamilyFailure.NETWORK)
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
        cache.deleteFamily(identity.userId, familyId)
        if (mutableState.value.snapshot?.family?.id == familyId) mutableState.value = FamilyState()
    }
    suspend fun clearAll() { mutableState.value = FamilyState(); cache.clearAll() }
    private fun requireCurrent(identity: ParentIdentity) { if (currentIdentity() != identity) throw AuthSessionRejected() }
    private fun validate(snapshot: FamilySnapshot, identity: ParentIdentity, familyId: String) {
        if (snapshot.family.version != 1 || snapshot.family.id != familyId || snapshot.membership.version != 1 ||
            snapshot.membership.familyId != familyId || snapshot.membership.userId != identity.userId ||
            snapshot.membership.status != "active" || snapshot.membership.role !in setOf("owner", "parent") ||
            snapshot.children.any { it.version != 1 || it.familyId != familyId } ||
            snapshot.devices.any { it.version != 1 || it.familyId != familyId || it.childId !in snapshot.children.map { child -> child.id } }) throw FamilyAccessDenied()
    }
}
