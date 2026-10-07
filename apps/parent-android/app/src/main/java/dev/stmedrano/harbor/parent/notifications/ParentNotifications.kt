package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable data class ParentReceipt(val registrationId: String, val route: ParentRoute, val receivedAt: Long)
data class ParentNotificationState(val confirmed: Boolean = false, val busy: Boolean = false, val message: String? = null,
    val receipt: ParentReceipt? = null)

class ParentNotifications(private val api: ParentFcmApi, private val tokens: FirebaseTokenProvider,
    val store: ParentRegistrationStore, private val currentIdentity: () -> ParentIdentity?,
    private val currentFamily: () -> String?, private val permissionAllowed: () -> Boolean,
    private val refresh: suspend (ParentHint) -> Boolean, private val render: (ParentHint) -> Unit,
    private val captureAccessToken: suspend () -> String = { error("No active Auth session") }) {
    private val mutableState = MutableStateFlow(ParentNotificationState())
    val state = mutableState.asStateFlow()
    private val operations = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var optedIdentity: ParentIdentity? = null
    private val seen = LinkedHashSet<ParentHint>()

    fun invalidate() = synchronized(lock) {
        generation++; optedIdentity = null; store.clearMarker(); seen.clear()
        mutableState.value = ParentNotificationState()
    }
    fun disableLocally() = synchronized(lock) { store.clearOptIn(); invalidate() }
    suspend fun enable(expectedIdentity: ParentIdentity? = null) = operations.withLock {
        val identity = currentIdentity()
        if (expectedIdentity != null && (identity != expectedIdentity || !store.optedIn(expectedIdentity))) return@withLock
        if (identity == null || !permissionAllowed()) {
            invalidate(); mutableState.value = ParentNotificationState(message = "Sign in and allow notifications first.")
            return@withLock
        }
        val ticket = synchronized(lock) {
            generation++; optedIdentity = identity; store.setOptedIn(identity, true); store.clearMarker()
            mutableState.value = ParentNotificationState(busy = true)
            generation
        }
        try {
            val token = withTimeoutOrNull(10000) { tokens.token() } ?: error("Token request timed out")
            register(identity, ticket, token)
        } catch (cancelled: CancellationException) { failed(identity, ticket); throw cancelled }
        catch (_: Exception) { failed(identity, ticket) }
    }
    suspend fun onTokenChanged(token: String) = operations.withLock {
        val identity = currentIdentity() ?: return@withLock
        val ticket = synchronized(lock) {
            if (optedIdentity != identity || !permissionAllowed()) return@withLock
            generation++; store.clearMarker(); mutableState.value = ParentNotificationState(busy = true); generation
        }
        register(identity, ticket, token)
    }
    private suspend fun register(identity: ParentIdentity, ticket: Long, token: String) {
        if (token.isBlank() || token.length > 4096 || token.any(Char::isISOControl)) { failed(identity, ticket); return }
        val installation = store.installationId()
        repeat(3) { attempt ->
            if (!current(identity, ticket)) return
            try {
                val reply = withTimeoutOrNull(10000) { api.register(installation, token) } ?: error("Registration timed out")
                require(reply.active && ParentMessageParser.uuid(reply.registrationId))
                synchronized(lock) {
                    if (current(identity, ticket)) {
                        store.confirm(identity, reply.registrationId)
                        mutableState.value = ParentNotificationState(confirmed = true)
                    }
                }
                return
            } catch (cancelled: CancellationException) { failed(identity, ticket); throw cancelled }
            catch (_: Exception) { if (attempt < 2 && current(identity, ticket)) delay(250) }
        }
        failed(identity, ticket)
    }
    suspend fun remove() {
        val identity = currentIdentity() ?: run { disableLocally(); return }
        val installation = store.installationId()
        disableLocally()
        val ticket = synchronized(lock) { generation }
        val removed = withTimeoutOrNull(10000) {
            try { api.remove(installation, captureAccessToken()); true }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        } == true
        synchronized(lock) {
            if (current(identity, ticket)) mutableState.value = ParentNotificationState(message = if (removed) "Registration removed." else "Local registration cleared. Backend cleanup is unconfirmed.")
        }
    }
    suspend fun onMessage(data: Map<String, String>): Boolean {
        val hint = ParentMessageParser.parse(data) ?: return false
        val identity = currentIdentity() ?: return false
        val ticket = synchronized(lock) { generation }
        if (!allowed(identity, ticket, hint) || synchronized(lock) { hint in seen }) return false
        if (!refreshed(hint)) return false
        return synchronized(lock) {
            if (!allowed(identity, ticket, hint) || hint in seen || !permissionAllowed()) false
            else { render(hint); seen.add(hint); if (seen.size > 100) seen.remove(seen.first())
                mutableState.value = state.value.copy(receipt = ParentReceipt(hint.registrationId, hint.route, System.currentTimeMillis()))
                true }
        }
    }
    suspend fun onTap(hint: ParentHint): Boolean {
        val identity = currentIdentity() ?: return false
        val ticket = synchronized(lock) { generation }
        return allowed(identity, ticket, hint) && refreshed(hint) && allowed(identity, ticket, hint)
    }
    private suspend fun refreshed(hint: ParentHint) = try {
        withTimeoutOrNull(10000) { refresh(hint) } == true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }
    private fun allowed(identity: ParentIdentity, ticket: Long, hint: ParentHint) = current(identity, ticket) &&
        hint.route.familyId != null && hint.route.familyId == currentFamily() && store.marker(identity)?.registrationId == hint.registrationId
    private fun current(identity: ParentIdentity, ticket: Long) = synchronized(lock) { generation == ticket && currentIdentity() == identity }
    private fun failed(identity: ParentIdentity, ticket: Long) = synchronized(lock) {
        if (current(identity, ticket)) mutableState.value = ParentNotificationState(message = "Registration is unconfirmed. Retry when connected.")
    }
}
