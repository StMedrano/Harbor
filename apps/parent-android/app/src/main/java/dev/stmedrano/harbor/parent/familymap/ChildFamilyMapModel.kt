package dev.stmedrano.harbor.parent.familymap

import dev.stmedrano.harbor.parent.checkin.CheckInTransport
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** [loaded] is false until the first answer, so the screen never claims "no one is sharing" before it knows. */
data class FamilyMapUiState(
    val loaded: Boolean = false,
    val enabled: Boolean = true,
    val people: List<FamilyMapPerson> = emptyList(),
    /** True when the last refresh failed and the people shown are the previous answer. */
    val offline: Boolean = false,
    val receivedAtMs: Long? = null,
)

/**
 * The child side of the family map for one profile. It only reads: a child cannot change what anyone shares, and a
 * parent's switch decides whether this map has any people on it.
 */
class ChildFamilyMapModel(
    private val transport: CheckInTransport,
    private val current: (ProfileLease) -> Boolean,
    private val now: () -> Long,
) {
    private val lock = Mutex()
    private val mutable = MutableStateFlow(FamilyMapUiState())
    val state: StateFlow<FamilyMapUiState> = mutable.asStateFlow()

    private fun valid(lease: ProfileLease) = lease.role == ProfileRole.CHILD && current(lease)

    fun clear() { mutable.value = FamilyMapUiState() }

    suspend fun refresh(lease: ProfileLease) = lock.withLock {
        if (!valid(lease)) return@withLock
        try {
            val reply = decodeFamilyMap(transport.send(lease, "get-family-map", """{"version":1}"""))
            if (valid(lease)) mutable.value = FamilyMapUiState(loaded = true, enabled = reply.enabled, people = reply.people, offline = false, receivedAtMs = now())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (valid(lease)) mutable.value = mutable.value.copy(offline = true) }
    }
}
