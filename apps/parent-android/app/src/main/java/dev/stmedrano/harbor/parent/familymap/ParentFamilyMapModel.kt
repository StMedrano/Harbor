package dev.stmedrano.harbor.parent.familymap

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

interface ParentFamilyMapApi {
    suspend fun settings(familyId: String): FamilyMapSettingsV1
    suspend fun setChildrenCanView(familyId: String, value: Boolean): FamilyMapSettingsV1
    suspend fun report(report: ParentLocationReportV1): ParentLocationReceiptV1
    suspend fun stop(familyId: String): ParentLocationReceiptV1

    /** The family on one map. Defaults to nothing so an implementation that does not read it still compiles. */
    suspend fun people(familyId: String, selfUserId: String): List<FamilyMapPerson> = emptyList()
}

enum class FamilyMapProblem { COULD_NOT_LOAD, COULD_NOT_SAVE, COULD_NOT_SHARE }

/** [loaded] is false until the server has answered, so the switches never show a guess. */
data class ParentFamilyMapState(
    val familyId: String? = null,
    val loaded: Boolean = false,
    val childrenCanView: Boolean = true,
    /** Set while this parent's place is stored for the family. Null means not sharing. */
    val mySharing: MySharing? = null,
    val busy: Boolean = false,
    val problem: FamilyMapProblem? = null,
    /** Everyone in the family: each child, and each parent sharing right now. */
    val people: List<FamilyMapPerson> = emptyList(),
    val peopleLoaded: Boolean = false,
    /** True when the last read of the map failed and [people] is the previous answer. */
    val peopleOffline: Boolean = false,
)

/**
 * Memory-only parent controls for the family map: whether children can see it, and whether this parent shares a place.
 * Every reply is fenced by the captured identity and family, so a sign-out or a family switch drops it.
 */
class ParentFamilyMapModel(private val api: ParentFamilyMapApi, private val currentIdentity: () -> ParentIdentity?, private val now: () -> Long) {
    private val mutable = MutableStateFlow(ParentFamilyMapState())
    val state: StateFlow<ParentFamilyMapState> = mutable.asStateFlow()
    private val generation = AtomicLong()

    private fun scoped(familyId: String) = mutable.value.takeIf { it.familyId == familyId }

    suspend fun load(identity: ParentIdentity, familyId: String) {
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live()) return
        val before = scoped(familyId) ?: ParentFamilyMapState(familyId = familyId)
        mutable.value = before
        try {
            val reply = api.settings(familyId)
            if (live()) mutable.value = ParentFamilyMapState(familyId, loaded = true, childrenCanView = reply.childrenCanView, mySharing = reply.mySharing,
                people = before.people, peopleLoaded = before.peopleLoaded, peopleOffline = before.peopleOffline)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FamilyAccessDenied) { if (live()) mutable.value = ParentFamilyMapState(familyId, problem = FamilyMapProblem.COULD_NOT_LOAD) }
        catch (_: Exception) { if (live()) mutable.value = before.copy(problem = FamilyMapProblem.COULD_NOT_LOAD) }
    }

    suspend fun setChildrenCanView(identity: ParentIdentity, familyId: String, value: Boolean) {
        val before = scoped(familyId)?.takeIf { it.loaded && !it.busy } ?: return
        mutable.value = before.copy(busy = true, problem = null)
        try {
            val reply = api.setChildrenCanView(familyId, value)
            if (currentIdentity() == identity && scoped(familyId) != null) mutable.value = ParentFamilyMapState(familyId, loaded = true, childrenCanView = reply.childrenCanView, mySharing = reply.mySharing,
                people = before.people, peopleLoaded = before.peopleLoaded, peopleOffline = before.peopleOffline)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (currentIdentity() == identity && scoped(familyId) != null) mutable.value = before.copy(busy = false, problem = FamilyMapProblem.COULD_NOT_SAVE) }
    }

    /**
     * Stores this parent's latest place. [interactive] is true when a tap caused it, so a failure is shown; the quiet
     * refreshes while Harbor is open stay silent and simply try again on the next fix.
     */
    suspend fun share(identity: ParentIdentity, familyId: String, label: String, fix: FamilyMapFix, interactive: Boolean): Boolean {
        val before = scoped(familyId)?.takeIf { it.loaded } ?: return false
        if (interactive && before.busy) return false
        val report = try { validateParentLocationReport(ParentLocationReportV1(1, familyId, label, fix), now()) }
        catch (_: IllegalArgumentException) { if (interactive) mutable.value = before.copy(problem = FamilyMapProblem.COULD_NOT_SHARE); return false }
        if (interactive) mutable.value = before.copy(busy = true, problem = null)
        try {
            val receipt = api.report(report)
            if (currentIdentity() != identity || scoped(familyId) == null) return false
            mutable.value = mutable.value.copy(busy = false, problem = null, mySharing = MySharing(label, fix.at))
            return receipt.confirmed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (interactive && currentIdentity() == identity && scoped(familyId) != null) mutable.value = before.copy(busy = false, problem = FamilyMapProblem.COULD_NOT_SHARE)
            return false
        }
    }

    /** Stops sharing and deletes the stored place. */
    suspend fun stop(identity: ParentIdentity, familyId: String) {
        val before = scoped(familyId)?.takeIf { it.loaded && !it.busy } ?: return
        mutable.value = before.copy(busy = true, problem = null)
        try {
            api.stop(familyId)
            if (currentIdentity() == identity && scoped(familyId) != null) mutable.value = mutable.value.copy(busy = false, mySharing = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (currentIdentity() == identity && scoped(familyId) != null) mutable.value = before.copy(busy = false, problem = FamilyMapProblem.COULD_NOT_SHARE) }
    }

    /**
     * Re-reads the family map. It never clears what is already shown: a failed read keeps the last answer and marks
     * it, because an empty map would read as "nobody is anywhere" rather than "this did not load".
     */
    suspend fun refreshPeople(identity: ParentIdentity, familyId: String) {
        try {
            val people = api.people(familyId, identity.userId)
            if (currentIdentity() == identity && mutable.value.familyId == familyId) {
                mutable.value = mutable.value.copy(people = people, peopleLoaded = true, peopleOffline = false)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (currentIdentity() == identity && mutable.value.familyId == familyId) {
                mutable.value = mutable.value.copy(peopleOffline = true)
            }
        }
    }

    /** Sign-out, membership loss or role change: drop everything and invalidate in-flight calls. */
    fun clear() { generation.incrementAndGet(); mutable.value = ParentFamilyMapState() }
}
