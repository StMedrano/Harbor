package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

enum class FamilyFailure { ACCESS_DENIED, NETWORK }
/**
 * What the family screens show. [snapshot] only ever holds an answer read from the server in this run of the app: nothing
 * saved on the phone is shown, so a parent sees the loading screen rather than information that may be out of date.
 */
data class FamilyState(val snapshot: FamilySnapshot? = null, val loading: Boolean = false, val failure: FamilyFailure? = null)

class FamilyRepository(private val api: ParentApi, private val cache: FamilyCacheDao, private val currentIdentity: () -> ParentIdentity?) {
    private val mutableState = MutableStateFlow(FamilyState())
    val state = mutableState.asStateFlow()
    private val generation = AtomicLong(0)
    private val commits = Mutex()
    private val frames = Any()

    suspend fun refresh(identity: ParentIdentity, familyId: String): FamilySnapshot {
        requireCurrent(identity)
        val ticket = synchronized(frames) { generation.incrementAndGet() }
        synchronized(frames) {
            requireTicket(identity, ticket)
            mutableState.value = FamilyState(answered(identity, familyId), loading = true)
        }
        try {
            val snapshot = api.readFamily(familyId)
            requireTicket(identity, ticket)
            validate(snapshot, identity, familyId)
            synchronized(frames) { requireTicket(identity, ticket); mutableState.value = FamilyState(snapshot) }
            return snapshot
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: FamilyAccessDenied) {
            synchronized(frames) { if (current(identity, ticket)) mutableState.value = FamilyState(failure = FamilyFailure.ACCESS_DENIED) }
            throw denied
        } catch (failure: Exception) {
            synchronized(frames) { if (current(identity, ticket)) mutableState.value = FamilyState(answered(identity, familyId), failure = FamilyFailure.NETWORK) }
            throw failure
        }
    }

    /** The answer already on screen, kept through a refresh so the page does not blink. Never one from an earlier run. */
    private fun answered(identity: ParentIdentity, familyId: String) =
        mutableState.value.snapshot?.takeIf { it.family.id == familyId && it.membership.userId == identity.userId }

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
    /** The first read of a run is on its way and there is nothing on screen yet. */
    fun showLoading(identity: ParentIdentity) = synchronized(frames) {
        requireCurrent(identity)
        if (mutableState.value.snapshot == null) { generation.incrementAndGet(); mutableState.value = FamilyState(loading = true) }
    }
    /** The read failed: say so, with what was already read in this run if there is any, and nothing older. */
    fun showFailure(identity: ParentIdentity) = synchronized(frames) {
        requireCurrent(identity)
        generation.incrementAndGet()
        mutableState.value = FamilyState(mutableState.value.snapshot, failure = FamilyFailure.NETWORK)
    }
    /** Earlier versions saved each family on the phone to show while loading. Nothing reads those copies now. */
    suspend fun forgetSavedFamilies() = commits.withLock { cache.deleteSnapshots() }
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
