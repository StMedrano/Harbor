package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParentNotificationsTest {
    private val parent = ParentIdentity("parent-a", "session-a")
    private class Values : AuthValues {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
        override fun clear() = map.clear()
    }
    private class Tokens : FirebaseTokenProvider {
        var fetches = 0
        override suspend fun token(): String { fetches++; return "synthetic-fcm-token" }
        override suspend fun deleteToken() {}
    }
    private open class Api : ParentFcmApi {
        val requests = mutableListOf<Pair<String, String>>()
        override suspend fun register(installationId: String, token: String): ParentRegistrationReply {
            requests += installationId to token
            return ParentRegistrationReply(REGISTRATION, true)
        }
        override suspend fun remove(installationId: String, accessToken: String) {}
    }
    private fun notifications(api: Api, tokens: Tokens, store: ParentRegistrationStore,
        identity: () -> ParentIdentity? = { parent }, permission: () -> Boolean = { true },
        refresh: suspend (ParentHint) -> Boolean = { true }, render: (ParentHint) -> Unit = {}) =
        ParentNotifications(api, tokens, store, identity, { FAMILY }, permission, refresh, render)

    @Test fun PermissionDenialNeverFetchesTokenOrRegisters() = runTest {
        val api = Api(); val tokens = Tokens(); val store = ParentRegistrationStore(Values()) { "installation" }
        val notifications = notifications(api, tokens, store, permission = { false })
        notifications.enable()
        assertEquals(0, tokens.fetches)
        assertTrue(api.requests.isEmpty())
        assertFalse(notifications.state.value.confirmed)
        assertNull(store.marker(parent))
    }

    @Test fun BackendFailureIsUnconfirmedAndRotationReusesInstallationAcrossRecreation() = runTest {
        val values = Values(); val tokens = Tokens()
        val failing = object : Api() {
            override suspend fun register(installationId: String, token: String): ParentRegistrationReply {
                super.register(installationId, token); error("network unavailable")
            }
        }
        val first = notifications(failing, tokens, ParentRegistrationStore(values) { "stable-installation" })
        first.enable()
        assertFalse(first.state.value.confirmed)
        assertTrue(failing.requests.isNotEmpty())
        assertTrue(failing.requests.all { it.first == "stable-installation" })
        val api = Api(); val store = ParentRegistrationStore(values) { "different-installation" }
        val retry = notifications(api, tokens, store)
        retry.enable()
        retry.onTokenChanged("rotated-token")
        assertTrue(retry.state.value.confirmed)
        assertEquals(listOf("stable-installation", "stable-installation"), api.requests.map { it.first })
        assertEquals("rotated-token", api.requests.last().second)
        assertEquals(REGISTRATION, store.marker(parent)?.registrationId)
        assertFalse(values.map.values.any { it.contains("synthetic-fcm-token") || it.contains("rotated-token") })
    }

    @Test fun AccountSwitchDuringRegistrationCannotCommitOldMarker() = runTest {
        var current: ParentIdentity? = parent
        val response = CompletableDeferred<ParentRegistrationReply>()
        val api = object : Api() {
            override suspend fun register(installationId: String, token: String) = response.await()
        }
        val store = ParentRegistrationStore(Values()) { "installation" }
        val notifications = notifications(api, Tokens(), store, identity = { current })
        val request = async { notifications.enable() }
        runCurrent()
        current = ParentIdentity("parent-b", "session-b")
        notifications.invalidate()
        response.complete(ParentRegistrationReply(REGISTRATION, true))
        request.await()
        assertNull(store.marker(parent))
        assertNull(store.marker(current!!))
        assertFalse(notifications.state.value.confirmed)
    }

    @Test fun OldRegistrationWrongFamilyDuplicateAndInaccessibleHintsDoNotRender() = runTest {
        var accessible = true; var refreshes = 0; var rendered = 0
        val notifications = notifications(Api(), Tokens(), ParentRegistrationStore(Values()) { "installation" },
            refresh = { refreshes++; accessible }, render = { rendered++ })
        notifications.enable()
        assertFalse(notifications.onMessage(envelope() + ("parentRegistrationId" to DEVICE)))
        assertFalse(notifications.onMessage(envelope(envelope().getValue("route").replace(FAMILY, DEVICE))))
        assertEquals(0, refreshes)
        assertTrue(notifications.onMessage(envelope()))
        assertFalse(notifications.onMessage(envelope()))
        assertEquals(1, rendered)
        accessible = false
        assertFalse(notifications.onMessage(envelope(envelope().getValue("route").replace(EVENT, DEVICE))))
        assertEquals(1, rendered)
        assertFalse(notifications.onTap(checkNotNull(ParentMessageParser.parse(envelope()))))
    }

    @Test fun CancelledRegistrationDoesNotLeaveBusyUiOrConfirmBinding() = runTest {
        val response = CompletableDeferred<ParentRegistrationReply>()
        val api = object : Api() { override suspend fun register(installationId: String, token: String) = response.await() }
        val store = ParentRegistrationStore(Values()) { "installation" }
        val notifications = notifications(api, Tokens(), store)
        val request = async { notifications.enable() }
        runCurrent()
        assertTrue(notifications.state.value.busy)
        request.cancelAndJoin()
        assertFalse(notifications.state.value.busy)
        assertFalse(notifications.state.value.confirmed)
        assertNull(store.marker(parent))
    }

    @Test fun AuthorizedReadFailureNeverRendersOrClaimsReceipt() = runTest {
        var rendered = 0
        val notifications = notifications(Api(), Tokens(), ParentRegistrationStore(Values()) { "installation" },
            refresh = { error("network unavailable") }, render = { rendered++ })
        notifications.enable()
        assertFalse(notifications.onMessage(envelope()))
        assertEquals(0, rendered)
    }

    @Test fun LocalOptOutClearsMarkerEvenWhenAccessCaptureFails() = runTest {
        val store = ParentRegistrationStore(Values()) { "installation" }
        val notifications = ParentNotifications(Api(), Tokens(), store, { parent }, { FAMILY }, { true },
            { true }, {}, captureAccessToken = { error("offline") })
        notifications.enable()
        assertTrue(notifications.state.value.confirmed)
        runCatching { notifications.remove() }
        assertFalse(notifications.state.value.confirmed)
        assertNull(store.marker(parent))
    }
}
