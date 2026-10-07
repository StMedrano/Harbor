package dev.stmedrano.harbor.parent.notifications

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.ParentRuntimeState
import dev.stmedrano.harbor.parent.ParentRuntime
import dev.stmedrano.harbor.parent.auth.*
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.*
import dev.stmedrano.harbor.parent.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ParentNotificationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun developmentReceiptViewExportsOnlyAnObservedReferenceOnExplicitTap() {
        val receipt = ParentReceipt("00000000-0000-4000-8000-000000000001",
            ParentRoute(1,"device.state.changed", resourceId = "00000000-0000-4000-8000-000000000002"), 1_000_000L)
        var exports = 0
        compose.setContent { HarborTheme { ParentApp {
            SettingsScreen(ParentNotificationState(confirmed = true, receipt = receipt), ParentRuntimeState(), true,
                {}, {}, {}, developmentReceipt = receipt, onExport = { exports++ })
        } } }
        compose.onNodeWithText("Development receipt evidence").performScrollTo().assertIsDisplayed()
        assertEquals(0,exports)
        compose.onNodeWithText("Export latest receipt").performScrollTo().performClick()
        assertEquals(1,exports)
        compose.onNodeWithText("Event reference: ${receipt.route.resourceId}").assertExists()
    }

    @Test fun notificationControlsReportUnconfirmedCleanupAndDisableDuringLogout() {
        var notification by mutableStateOf(ParentNotificationState())
        var runtime by mutableStateOf(ParentRuntimeState())
        var enables = 0
        var removals = 0
        var logouts = 0
        compose.setContent { HarborTheme { ParentApp {
            SettingsScreen(notification, runtime, available = true,
                onEnable = { enables++; notification = ParentNotificationState(message = "Sign in and allow notifications first.") },
                onRemove = { removals++; notification = ParentNotificationState(message = "Local registration cleared. Backend cleanup is unconfirmed.") },
                onSignOut = { logouts++; runtime = ParentRuntimeState(signingOut = true) })
        } } }
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        compose.onNodeWithText("Sign in and allow notifications first.").assertIsDisplayed()
        compose.onNodeWithText("Registration unconfirmed").assertIsDisplayed()
        compose.onNodeWithText("Remove notification registration").performScrollTo().performClick()
        compose.onNodeWithText("Local registration cleared. Backend cleanup is unconfirmed.").assertIsDisplayed()
        compose.onNodeWithText("Sign out this device").performScrollTo().performClick()
        compose.onNodeWithText("Signing out…").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Enable notifications").assertIsNotEnabled()
        compose.onNodeWithText("Remove notification registration").assertIsNotEnabled()
        compose.onNodeWithText("Sign out this device").assertIsNotEnabled()
        assertEquals(1, enables); assertEquals(1, removals); assertEquals(1, logouts)
    }

    @Test fun nativeControlsUsePermissionGateAndOfflineLogoutErasesVerifiedSession() {
        val owner = ParentIdentity("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002")
        val values = object : AuthValues {
            val entries = mutableMapOf<String, String>()
            override fun read(key: String) = entries[key]
            override fun write(key: String, value: String?) { if (value == null) entries.remove(key) else entries[key] = value }
            override fun clear() = entries.clear()
        }
        val secrets = SecureAuthStore(values, object : AuthCipher {
            override fun encrypt(slot: String, value: ByteArray) = value
            override fun decrypt(slot: String, value: ByteArray) = value
        })
        val gateway = object : AuthGateway {
            var session: UserSession? = UserSession("synthetic-access", "synthetic-refresh", expiresIn = 3600, tokenType = "bearer")
            override suspend fun restoreStoredSession() = session
            override suspend fun fetchVerifiedIdentity(session: UserSession, expectedEmail: String?, expectedUserId: String?) = owner
            override suspend fun clearLocalSession() { session = null }
            override suspend fun signOutCurrent(): Unit = error("offline")
            override suspend fun signIn(email: String, password: String) = error("Unused")
            override suspend fun signUp(email: String, password: String) = error("Unused")
            override suspend fun requestRecovery(email: String) = error("Unused")
            override suspend fun exchangeCode(code: String) = error("Unused")
            override suspend fun changePassword(password: String) = error("Unused")
            override suspend fun refresh() = error("Unused")
        }
        val auth = ParentAuthRepository(gateway, secrets)
        runBlocking { auth.restore() }
        secrets.write("verifier", "synthetic-only")
        var allowed = false
        var failing = true
        var fetched = 0
        var registered = 0
        var deleted = 0
        val release = CompletableDeferred<Unit>()
        val marker = ParentRegistrationStore(object : AuthValues {
            val entries = mutableMapOf<String, String>()
            override fun read(key: String) = entries[key]
            override fun write(key: String, value: String?) { if (value == null) entries.remove(key) else entries[key] = value }
            override fun clear() = entries.clear()
        })
        val installation = marker.installationId()
        val notifications = ParentNotifications(object : ParentFcmApi {
            override suspend fun register(installationId: String, token: String): ParentRegistrationReply {
                registered++; if (failing) error("offline")
                return ParentRegistrationReply("00000000-0000-4000-8000-000000000003", true)
            }
            override suspend fun remove(installationId: String, accessToken: String): Unit = error("offline")
        }, object : FirebaseTokenProvider {
            override suspend fun token(): String { fetched++; return "synthetic-fcm" }
            override suspend fun deleteToken() { deleted++ }
        }, marker, { auth.identity.value }, { null }, { allowed }, { false }, {}, { auth.withAccessToken { it } })
        val runtime = ParentRuntime({ auth.identity.value }, { auth.withAccessToken { it } }, notifications::invalidate,
            {}, {}, { _, _ -> release.await(); error("offline") }, { deleted++ }, auth::signOutCurrent,
            { auth.clearLocal(); marker.clearMarker() })
        compose.setContent { HarborTheme { ParentApp {
            val identity by auth.identity.collectAsState()
            val state by runtime.state.collectAsState()
            val notification by notifications.state.collectAsState()
            val scope = rememberCoroutineScope()
            if (identity != null && !state.signingOut) androidx.compose.material3.Text("Verified parent")
            SettingsScreen(notification, state, true,
                { scope.launch { runtime.authAction { notifications.enable() } } },
                { scope.launch { runtime.authAction { notifications.remove() } } },
                { scope.launch { runtime.signOutCurrent() } })
        } } }
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        compose.waitUntil(5000) { notifications.state.value.message != null }
        assertEquals(0, fetched); assertEquals(0, registered)
        compose.runOnUiThread { allowed = true }
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        compose.waitUntil(5000) { registered == 3 && !notifications.state.value.busy }
        assertFalse(notifications.state.value.confirmed)
        assertNull(marker.marker(owner))
        compose.runOnUiThread { failing = false }
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        compose.waitUntil(5000) { notifications.state.value.confirmed }
        assertNotNull(marker.marker(owner))
        compose.onNodeWithText("Sign out this device").performScrollTo().performClick()
        compose.waitUntil(5000) { runtime.state.value.signingOut }
        compose.onNodeWithText("Verified parent").assertDoesNotExist()
        compose.onNodeWithText("Enable notifications").assertIsNotEnabled()
        assertNull(marker.marker(owner))
        release.complete(Unit)
        compose.waitUntil(5000) { !runtime.state.value.signingOut && auth.identity.value == null }
        assertFalse(runtime.state.value.cleanupConfirmed)
        assertNull(secrets.read("verifier"))
        assertEquals(1, deleted)
        assertEquals(installation, marker.installationId())
    }
}
