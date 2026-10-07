package dev.stmedrano.harbor.parent.security

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class SecurityPhase { IDLE, CONFIRMATION, STEP_UP, READY_TO_RETRY, PENDING, ACCEPTED, DENIED }
data class RevocationTarget(val familyId: String, val deviceId: String, val owner: ParentIdentity)
data class SecurityState(val phase: SecurityPhase = SecurityPhase.IDLE, val target: RevocationTarget? = null,
    val enrollment: TotpEnrollment? = null, val factors: List<String> = emptyList(), val busy: Boolean = false,
    val message: String? = null)

class SecurityViewModel(private val mfa: MfaGateway, private val currentIdentity: () -> ParentIdentity?,
    private val familyState: () -> FamilyState, private val revoke: suspend (String, String) -> Unit,
    private val refresh: suspend () -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    private val mutableState = MutableStateFlow(SecurityState())
    val state = mutableState.asStateFlow()
    fun targetName(): String? = state.value.target?.let { target -> familyState().snapshot?.devices?.firstOrNull { it.id == target.deviceId && it.familyId == target.familyId }?.displayName }
    private val operations = Mutex()
    private val frames = Any()
    private var generation = 0L
    private var readyAt: Long? = null

    fun clear() = synchronized(frames) { generation++; readyAt = null; mutableState.value = SecurityState() }
    fun requestRevocation(familyId: String, deviceId: String) = synchronized(frames) {
        generation++; readyAt = null
        val actor = currentIdentity()
        val target = actor?.let { RevocationTarget(familyId, deviceId, it) }
        mutableState.value = if (target != null && eligible(target)) state.value.copy(phase = SecurityPhase.CONFIRMATION,
            target = target, busy = false, message = "Confirm revocation of this device.")
        else state.value.copy(phase = SecurityPhase.DENIED, target = null, busy = false, message = "Refresh current access before revoking a device.")
    }
    suspend fun loadFactors() = perform { actor, ticket ->
        val factors = mfa.listFactors()
        publish(actor, ticket) { it.copy(factors = factors) }
    }
    suspend fun enrollTotp() = perform { actor, ticket ->
        val enrollment = mfa.enrollTotp()
        publish(actor, ticket) { it.copy(enrollment = enrollment, message = "Add this account to your authenticator, then verify its code.") }
    }
    suspend fun challenge(factorId: String, code: String) = perform { actor, ticket ->
        require(factorId.isNotBlank() && code.matches(Regex("[0-9]{6}")))
        mfa.challenge(factorId, code)
        synchronized(frames) {
            if (current(actor, ticket)) {
                readyAt = now()
                mutableState.value = state.value.copy(enrollment = null, factors = (state.value.factors + factorId).distinct(),
                    phase = if (state.value.target != null) SecurityPhase.READY_TO_RETRY else SecurityPhase.IDLE,
                    message = "Authenticator verified. Revocation requires a separate deliberate confirmation.")
            }
        }
    }
    suspend fun confirmRetry() = perform { actor, ticket ->
        val target = state.value.target ?: return@perform
        val phase = state.value.phase
        if (phase !in setOf(SecurityPhase.CONFIRMATION, SecurityPhase.READY_TO_RETRY)) return@perform
        if (!eligible(target) || target.owner != actor) {
            publish(actor, ticket) { it.copy(phase = SecurityPhase.DENIED, message = "Current access is unavailable. No revocation confirmed.") }
            return@perform
        }
        if (phase == SecurityPhase.READY_TO_RETRY && readyAt?.let { now() - it in 0..900_000 } != true) {
            publish(actor, ticket) { it.copy(phase = SecurityPhase.STEP_UP, message = "Verify a fresh authenticator code before retrying.") }
            return@perform
        }
        publish(actor, ticket) { it.copy(phase = SecurityPhase.PENDING, message = "Requesting revocation…") }
        try {
            revoke(target.familyId, target.deviceId)
        } catch (_: MfaRequired) {
            publish(actor, ticket) { it.copy(phase = SecurityPhase.STEP_UP, message = "Verify a fresh authenticator code, then deliberately retry revocation.") }
            return@perform
        }
        if (!eligible(target)) {
            publish(actor, ticket) { it.copy(phase = SecurityPhase.DENIED, message = "Access changed. Refresh before checking this result.") }
            return@perform
        }
        publish(actor, ticket) { it.copy(phase = SecurityPhase.ACCEPTED, message = "Revocation accepted by the backend. Refreshing device status.") }
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(actor, ticket) { it.copy(message = "Revocation accepted. Device status refresh is unconfirmed.") } }
    }
    private fun eligible(target: RevocationTarget): Boolean {
        val state = familyState()
        val snapshot = state.snapshot ?: return false
        return currentIdentity() == target.owner && !state.cached && !state.loading && state.failure == null &&
            snapshot.family.id == target.familyId && snapshot.membership.userId == target.owner.userId &&
            snapshot.membership.status == "active" && snapshot.membership.role in setOf("owner", "parent") &&
            snapshot.devices.any { it.id == target.deviceId && it.familyId == target.familyId && it.status == "active" }
    }
    private fun current(actor: ParentIdentity, ticket: Long) = generation == ticket && currentIdentity() == actor
    private fun publish(actor: ParentIdentity, ticket: Long, update: (SecurityState) -> SecurityState) = synchronized(frames) {
        if (current(actor, ticket)) mutableState.value = update(state.value)
    }
    private suspend fun perform(action: suspend (ParentIdentity, Long) -> Unit) = operations.withLock {
        val actor = currentIdentity() ?: return@withLock
        val ticket = synchronized(frames) { mutableState.value = state.value.copy(busy = true); generation }
        try { action(actor, ticket) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { publish(actor, ticket) { it.copy(phase = SecurityPhase.DENIED, message = "Request failed. No revocation confirmed.") } }
        finally { publish(actor, ticket) { it.copy(busy = false) } }
    }
}
