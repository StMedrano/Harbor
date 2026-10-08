package dev.stmedrano.harbor.parent.child

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChildRepository(private val backend: ChildBackend, private val store: ChildStore,
    private val key: ChildSigner, private val clock: () -> Long) {
    private val currentBinding = MutableStateFlow<ChildBinding?>(null)
    val binding = currentBinding.asStateFlow()
    private val mutableState = MutableStateFlow<ChildSyncState>(ChildSyncState.Stale(null))
    val state = mutableState.asStateFlow()
    private val operations = Mutex()
    private var record: ChildRecord? = null
    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private class Invalid(val reason: ChildFailure) : IllegalStateException()
    private fun validate(session: ChildAuthSession) {
        if (!session.anonymous || !uuid.matches(session.userId) || session.credentials.accessToken.isBlank() ||
            session.credentials.refreshToken.isBlank()) throw Invalid(ChildFailure.AUTH_INVALID)
    }
    private fun validate(binding: ChildBinding) {
        if (!listOf(binding.deviceId, binding.familyId, binding.childId).all(uuid::matches)) throw Invalid(ChildFailure.AUTH_INVALID)
    }
    private suspend fun sessionFor(value: ChildRecord): ChildRecord {
        validate(value.session)
        if (value.session.credentials.expiresAt > clock() + 30) return value
        val refreshed = backend.refresh(value.session)
        validate(refreshed)
        if (refreshed.userId != value.session.userId || refreshed.credentials.expiresAt <= clock()) throw Invalid(ChildFailure.AUTH_INVALID)
        return value.copy(session = refreshed).also { store.save(it); record = it }
    }
    suspend fun restore() = operations.withLock {
        currentBinding.value = null
        record = null
        try {
            val saved = store.load()
            if (saved == null) {
                mutableState.value = when {
                    store.claimPending -> ChildSyncState.Blocked(ChildFailure.UNKNOWN_OUTCOME)
                    store.hasHistory -> ChildSyncState.Blocked(ChildFailure.STORAGE_UNAVAILABLE)
                    else -> ChildSyncState.Stale(null)
                }
                return@withLock
            }
            record = saved
            if (saved.revoked) throw Invalid(ChildFailure.REVOKED)
            validate(saved.session)
            val binding = saved.binding
            if (binding == null) {
                mutableState.value = if (store.claimPending) ChildSyncState.Blocked(ChildFailure.UNKNOWN_OUTCOME) else ChildSyncState.Stale(null)
                return@withLock
            }
            validate(binding)
            if (!key.exists()) throw Invalid(ChildFailure.KEY_LOST)
            val active = sessionFor(saved)
            // A durable encrypted binding proves a received claim result. Only
            // finish its local marker; never submit another claim on reopen.
            if (store.claimPending) store.claimPending = false
            record = active
            currentBinding.value = binding
            mutableState.value = ChildSyncState.Stale(active.lastSuccessAt)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: Invalid) { mutableState.value = ChildSyncState.Blocked(invalid.reason) }
        catch (_: Exception) { mutableState.value = ChildSyncState.Blocked(ChildFailure.STORAGE_UNAVAILABLE) }
    }
    suspend fun pair(code: String): PairResult = operations.withLock {
        var claiming = false
        var awaitingAuthReply = false
        try {
            if (!code.matches(Regex("[0-9]{6}")) || currentBinding.value != null || store.load()?.binding != null) return@withLock PairResult.Rejected
            if (store.claimPending) return@withLock PairResult.UnknownOutcome
            val previous = store.load()
            if (previous == null && store.hasHistory) throw Invalid(ChildFailure.STORAGE_UNAVAILABLE)
            val session = previous?.let { sessionFor(it).session } ?: run {
                // A lost signup response may already have created an identity.
                // Persist the unknown-outcome guard before that first request.
                store.claimPending = true
                awaitingAuthReply = true
                backend.anonymousSignup().also { awaitingAuthReply = false }
            }
            validate(session)
            if (session.credentials.expiresAt <= clock()) throw Invalid(ChildFailure.AUTH_INVALID)
            val unpaired = ChildRecord(session, null)
            store.save(unpaired)
            record = unpaired
            val spki = key.publicKeySpki()
            store.claimPending = true
            claiming = true
            val binding = backend.claim(code, spki, session)
            validate(binding)
            val paired = unpaired.copy(binding = binding)
            store.save(paired)
            store.claimPending = false
            record = paired
            currentBinding.value = binding
            mutableState.value = ChildSyncState.Stale(null)
            PairResult.Confirmed(binding)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (denied: ChildRequestDenied) {
            if (denied.status in setOf(400, 401, 403, 409)) {
                store.claimPending = false
                PairResult.Rejected
            } else {
                mutableState.value = ChildSyncState.Blocked(ChildFailure.UNKNOWN_OUTCOME)
                PairResult.UnknownOutcome
            }
        } catch (invalid: Invalid) {
            mutableState.value = ChildSyncState.Blocked(invalid.reason)
            if (claiming || awaitingAuthReply) PairResult.UnknownOutcome else PairResult.Rejected
        } catch (_: Exception) {
            val unknown = claiming || awaitingAuthReply
            mutableState.value = ChildSyncState.Blocked(if (unknown) ChildFailure.UNKNOWN_OUTCOME else ChildFailure.STORAGE_UNAVAILABLE)
            if (unknown) PairResult.UnknownOutcome else PairResult.Rejected
        }
    }
    suspend fun registerFcm(expected: ChildBinding, token: String): Boolean = operations.withLock {
        if (mutableState.value is ChildSyncState.Blocked || currentBinding.value != expected ||
            token.isBlank() || token.length > 4096) return@withLock false
        try {
            if (!key.exists()) throw Invalid(ChildFailure.KEY_LOST)
            val active = sessionFor(checkNotNull(record))
            if (active.binding != expected || active.revoked) throw Invalid(ChildFailure.REVOKED)
            backend.registerFcm(expected, token, active.session)
            true // Registration is not signed-sync or delivery evidence.
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: Invalid) { mutableState.value = ChildSyncState.Blocked(invalid.reason); false }
        catch (denied: ChildRequestDenied) { handleDenied(denied); false }
        catch (_: Exception) { mutableState.value = ChildSyncState.Stale(record?.lastSuccessAt); false }
    }
    private fun handleDenied(denied: ChildRequestDenied) {
        if (denied.status == 403 && denied.code == "DEVICE_REVOKED") {
            mutableState.value = ChildSyncState.Blocked(ChildFailure.REVOKED)
            record?.copy(revoked = true)?.let {
                record = it
                try { store.save(it) }
                catch (_: Exception) { /* Stay blocked even if persistence is unavailable. */ }
            }
        } else mutableState.value = ChildSyncState.Blocked(ChildFailure.AUTH_INVALID)
    }
    suspend fun sync(expected: ChildBinding? = null): ChildSyncState = operations.withLock {
        if (expected != null && currentBinding.value != expected) return@withLock mutableState.value
        try {
            val binding = currentBinding.value ?: throw Invalid(ChildFailure.AUTH_INVALID)
            if (!key.exists()) throw Invalid(ChildFailure.KEY_LOST)
            val active = sessionFor(checkNotNull(record))
            if (active.revoked) throw Invalid(ChildFailure.REVOKED)
            val version = backend.sync(binding, active.session)
            require(version >= 0)
            val confirmed = active.copy(lastSuccessAt = clock(), desiredVersion = version)
            store.save(confirmed); record = confirmed
            mutableState.value = ChildSyncState.Fresh(checkNotNull(confirmed.lastSuccessAt), version)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: Invalid) { mutableState.value = ChildSyncState.Blocked(invalid.reason) }
        catch (denied: ChildRequestDenied) { handleDenied(denied) }
        catch (_: Exception) { mutableState.value = ChildSyncState.Stale(record?.lastSuccessAt) }
        state.value
    }
    internal suspend fun confirmRevocation(expected: ChildBinding) = operations.withLock {
        val value = checkNotNull(record)
        check(value.binding == expected)
        val revoked = value.copy(revoked = true)
        store.save(revoked); record = revoked
        mutableState.value = ChildSyncState.Blocked(ChildFailure.REVOKED)
    }
    suspend fun clearAfterConfirmedRevocation() = operations.withLock {
        check(record?.revoked == true) { "Confirmed revocation required" }
        store.clear()
        key.delete()
        store.claimPending = false
        record = null; currentBinding.value = null
        // Role transition owns clearing the role hint after authorized cleanup.
        mutableState.value = ChildSyncState.Blocked(ChildFailure.REVOKED)
    }
}
