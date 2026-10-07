package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.AuthSessionRejected
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

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
