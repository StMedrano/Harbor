package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

data class FamilyControlState(val families: List<FamilyV1> = emptyList(), val selectedChildId: String? = null, val busy: Boolean = false, val message: String? = null)
@Serializable private data class PendingFamily(val name: String, val idempotencyKey: String)

class FamilyViewModel(private val api: ParentApi, val repository: FamilyRepository,
    private val children: PendingChildCreation, val pairing: PairingModel, private val store: SecureAuthStore,
    private val currentIdentity: () -> ParentIdentity?, private val newKey: () -> String = { UUID.randomUUID().toString() }) {
    private val mutableState = MutableStateFlow(FamilyControlState())
    val state = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var owner: ParentIdentity? = null

    suspend fun load(identity: ParentIdentity) = operation(identity) {
        if (owner != identity) { mutableState.value = FamilyControlState(busy = true); owner = identity; pairing.clear() }
        try {
            val families = api.listFamilies()
            requireCurrent(identity)
            mutableState.value = state.value.copy(families = families)
            val remembered = store.read("selected-family:${identity.userId}")
            val selected = families.firstOrNull { it.id == remembered } ?: families.firstOrNull()
            if (selected != null) selectUnlocked(identity, selected.id)
            else repository.clearAll()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            store.read("selected-family:${identity.userId}")?.let { repository.showCached(identity, it) }
            throw failure
        }
    }

    suspend fun selectFamily(identity: ParentIdentity, familyId: String) = operation(identity) { selectUnlocked(identity, familyId) }
    private suspend fun selectUnlocked(identity: ParentIdentity, familyId: String) {
        val snapshot = repository.refresh(identity, familyId)
        store.write("selected-family:${identity.userId}", familyId)
        mutableState.value = state.value.copy(selectedChildId = snapshot.children.firstOrNull()?.id)
        pairing.clear()
    }
    suspend fun refresh(identity: ParentIdentity) = operation(identity) {
        val family = repository.state.value.snapshot?.family?.id ?: store.read("selected-family:${identity.userId}") ?: return@operation
        val snapshot = repository.refresh(identity, family)
        if (state.value.selectedChildId !in snapshot.children.map { it.id }) mutableState.value = state.value.copy(selectedChildId = snapshot.children.firstOrNull()?.id)
        pairing.observe(identity, snapshot, cached = false)
    }

    suspend fun createFamily(identity: ParentIdentity, displayName: String) = operation(identity) {
        val name = displayName.trim()
        require(name.codePointCount(0, name.length) in 1..100 && name.none(Char::isISOControl))
        val slot = "pending-family:${identity.userId}"
        val pending = store.read(slot)?.let { Json.decodeFromString<PendingFamily>(it) } ?: PendingFamily(name, newKey()).also { store.write(slot, Json.encodeToString(it)) }
        require(pending.name == name) { "Cancel the pending family request before changing its name" }
        val created = api.createFamily(pending.name, pending.idempotencyKey)
        requireCurrent(identity)
        store.write(slot, null)
        store.write("selected-family:${identity.userId}", created.familyId)
        val families = api.listFamilies()
        mutableState.value = state.value.copy(families = families)
        selectUnlocked(identity, created.familyId)
    }
    suspend fun cancelFamily(identity: ParentIdentity) = operation(identity) { store.write("pending-family:${identity.userId}", null) }
    suspend fun submitChild(identity: ParentIdentity, name: String) = operation(identity) {
        requireOnline()
        val familyId = checkNotNull(repository.state.value.snapshot).family.id
        val child = children.submit(identity, familyId, name)
        repository.refresh(identity, familyId)
        mutableState.value = state.value.copy(selectedChildId = child.id)
    }
    suspend fun cancelChild(identity: ParentIdentity) = operation(identity) {
        repository.state.value.snapshot?.family?.id?.let { children.cancel(identity, it) }
    }
    fun selectChild(childId: String) {
        require(repository.state.value.snapshot?.children?.any { it.id == childId } == true)
        mutableState.value = state.value.copy(selectedChildId = childId)
        pairing.clear()
    }
    suspend fun issuePairing(identity: ParentIdentity) = operation(identity) {
        requireOnline()
        pairing.issue(identity, checkNotNull(state.value.selectedChildId))
    }
    suspend fun clear() { owner = null; mutableState.value = FamilyControlState(); pairing.clear(); repository.clearAll() }
    private fun requireOnline() { require(!repository.state.value.cached && repository.state.value.failure == null && repository.state.value.snapshot != null) { "Refresh online before making changes" } }
    private fun requireCurrent(identity: ParentIdentity) { if (currentIdentity() != identity) throw AuthSessionRejected() }
    private suspend fun operation(identity: ParentIdentity, block: suspend () -> Unit) = mutex.withLock {
        requireCurrent(identity)
        mutableState.value = state.value.copy(busy = true, message = null)
        try { block(); requireCurrent(identity) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { mutableState.value = state.value.copy(message = "Request failed. Refresh or retry the same request."); throw failure }
        finally { mutableState.value = state.value.copy(busy = false) }
    }
}

data class PairingState(val childId: String? = null, val code: PairingCode? = null, val enrolled: Boolean = false, val loading: Boolean = false)

class PairingModel(private val api: ParentApi, private val currentIdentity: () -> ParentIdentity?, private val now: () -> Long = System::currentTimeMillis) {
    private val mutableState = MutableStateFlow(PairingState())
    val state = mutableState.asStateFlow()
    suspend fun issue(identity: ParentIdentity, childId: String) {
        requireCurrent(identity)
        mutableState.value = PairingState(childId, loading = true)
        try {
            val code = api.createPairing(childId)
            requireCurrent(identity)
            require(code.code.matches(Regex("\\d{6}")) && Instant.parse(code.expiresAt).toEpochMilliseconds() > now()) { "Pairing code rejected" }
            mutableState.value = PairingState(childId, code)
        } finally { mutableState.value = mutableState.value.copy(loading = false) }
    }
    fun expired() = state.value.code?.let { Instant.parse(it.expiresAt).toEpochMilliseconds() <= now() } ?: true
    fun observe(identity: ParentIdentity, snapshot: FamilySnapshot, cached: Boolean) {
        requireCurrent(identity)
        if (cached || snapshot.membership.userId != identity.userId || snapshot.membership.status != "active") return
        mutableState.value = state.value.copy(enrolled = snapshot.devices.any { it.childId == state.value.childId && it.status == "active" })
    }
    fun clear() { mutableState.value = PairingState() }
    private fun requireCurrent(identity: ParentIdentity) { if (currentIdentity() != identity) throw AuthSessionRejected() }
}
