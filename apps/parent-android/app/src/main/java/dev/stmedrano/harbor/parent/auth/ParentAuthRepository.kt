package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

class ParentAuthRepository(
    private val gateway: AuthGateway,
    private val store: SecureAuthStore,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val currentIdentity = MutableStateFlow<ParentIdentity?>(null)
    val identity: StateFlow<ParentIdentity?> = currentIdentity.asStateFlow()
    private var session: UserSession? = null
    private val router = AuthCallbackRouter(now)

    suspend fun signIn(email: String, password: String) = mutex.withLock {
        require(store.transaction == null) { "Complete or cancel the pending email flow first" }
        try { accept(gateway.signIn(normalizeEmail(email), password), normalizeEmail(email), null) }
        catch (failure: Exception) { clearUnlocked(); throw failure }
    }

    suspend fun restore() = mutex.withLock {
        try {
            val restored = gateway.restoreStoredSession() ?: run { clearInvalidSession(); return@withLock }
            val refreshed = if (restored.expiresAt.toEpochMilliseconds() <= now()) gateway.refresh() else restored
            accept(refreshed, null, null)
        } catch (_: AuthSessionRejected) { clearInvalidSession() }
        catch (_: AuthStorageLost) { clearUnlocked() }
    }

    suspend fun <T> withAccessToken(block: suspend (String) -> T): T = mutex.withLock {
        var active = session ?: throw AuthSessionRejected()
        if (active.expiresAt.toEpochMilliseconds() <= now()) {
            try {
                val previous = currentIdentity.value ?: throw AuthSessionRejected()
                active = gateway.refresh()
                accept(active, null, previous.userId)
                if (currentIdentity.value != previous) throw AuthSessionRejected()
            } catch (failure: AuthSessionRejected) { clearUnlocked(); throw failure }
            catch (failure: AuthStorageLost) { clearUnlocked(); throw failure }
        }
        block(active.accessToken)
    }

    suspend fun beginSignup(email: String, password: String) = mutex.withLock {
        prepareFlow()
        val normalized = normalizeEmail(email)
        val started = now()
        try {
            val subject = gateway.signUp(normalized, password)
            store.transaction = AuthTransaction(AuthKind.SIGNUP, normalized, subject, started)
        } catch (failure: Exception) { clearFlow(); throw failure }
    }

    suspend fun beginRecovery(email: String) = mutex.withLock {
        prepareFlow()
        val normalized = normalizeEmail(email)
        val started = now()
        try {
            gateway.requestRecovery(normalized)
            store.transaction = AuthTransaction(AuthKind.RECOVERY, normalized, currentIdentity.value?.userId, started)
        } catch (failure: Exception) { clearFlow(); throw failure }
    }

    suspend fun consumeCallback(uri: String) = mutex.withLock {
        val callback = router.parse(uri, store.transaction) ?: error("Email callback rejected; request a fresh link")
        // Consume before network exchange: a crash or duplicate Intent cannot re-authorize the same callback.
        store.transaction = null
        try {
            val returned = gateway.exchangeCode(callback.code)
            accept(returned, callback.transaction.expectedEmail, callback.transaction.knownSubject)
            if (callback.transaction.kind == AuthKind.RECOVERY) {
                store.transaction = callback.transaction.copy(acceptedRecoverySubject = currentIdentity.value!!.userId)
            }
        } catch (failure: Exception) { clearUnlocked(); throw failure }
    }

    suspend fun changePassword(password: String) = mutex.withLock {
        val transaction = store.transaction ?: error("Verify a recovery link first")
        val subject = transaction.acceptedRecoverySubject ?: error("Verify a recovery link first")
        require(transaction.kind == AuthKind.RECOVERY && now() - transaction.startedAtMillis in 0..900_000)
        require(password.isNotBlank())
        val active = session ?: gateway.restoreStoredSession() ?: throw AuthSessionRejected()
        accept(active, transaction.expectedEmail, subject)
        gateway.changePassword(password)
        clearFlow()
    }

    suspend fun clearLocal() = mutex.withLock { clearUnlocked() }
    suspend fun acceptMfaSession(owner: ParentIdentity, value: UserSession, persist: suspend (UserSession) -> Unit) = mutex.withLock {
        // Verify the returned session before importing it into the SDK/store.
        // A late challenge must leave a newer account/session untouched.
        if (currentIdentity.value != owner) throw AuthSessionRejected()
        try {
            val verified = gateway.fetchVerifiedIdentity(value, null, owner.userId)
            if (verified != owner) throw AuthSessionRejected()
            persist(value)
            session = value
            currentIdentity.value = verified
        } catch (failure: Exception) { clearUnlocked(); throw failure }
    }
    suspend fun signOutCurrent(owner: ParentIdentity) = mutex.withLock {
        // The runtime blocks/cancels new Auth work before calling this bridge.
        // A stale screen cannot end a different verified session.
        if (currentIdentity.value != owner) throw AuthSessionRejected()
        try { gateway.signOutCurrent() } finally { clearUnlocked() }
    }
    suspend fun cancelEmailFlow() = mutex.withLock { clearFlow() }
    fun hasVerifiedRecovery(): Boolean = store.transaction?.let {
        it.kind == AuthKind.RECOVERY && it.acceptedRecoverySubject != null &&
            now() - it.startedAtMillis in 0..900_000
    } == true

    private suspend fun accept(value: UserSession, expectedEmail: String?, expectedUserId: String?) {
        val verified = gateway.fetchVerifiedIdentity(value, expectedEmail, expectedUserId)
        session = value
        currentIdentity.value = verified
    }

    private fun prepareFlow() {
        val pending = store.transaction
        if (pending != null && now() - pending.startedAtMillis !in 0..900_000) clearFlow()
        require(store.transaction == null) { "Only one pending email flow is supported" }
    }

    private fun clearFlow() { store.transaction = null; store.write("verifier", null) }
    private suspend fun clearInvalidSession() {
        val pending = store.transaction
        if (pending != null && pending.acceptedRecoverySubject == null && now() - pending.startedAtMillis in 0..900_000) {
            session = null
            currentIdentity.value = null
            // Auth.clearSession also deletes CodeVerifierCache. Our configured
            // cache retains only this pending flow while SDK memory/session clear.
            store.retainingVerifier { gateway.clearLocalSession() }
        } else clearUnlocked()
    }
    private suspend fun clearUnlocked() {
        session = null
        currentIdentity.value = null
        try { gateway.clearLocalSession() } finally { store.clear() }
    }

    private fun normalizeEmail(value: String): String = value.trim().lowercase(Locale.ROOT).also {
        require(it.isNotBlank() && it.contains('@') && it.none(Char::isISOControl)) { "Enter an email address" }
    }
}
