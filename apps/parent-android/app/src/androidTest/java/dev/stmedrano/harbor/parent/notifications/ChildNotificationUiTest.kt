package dev.stmedrano.harbor.parent.notifications

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.google.android.gms.tasks.Tasks
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.child.*
import dev.stmedrano.harbor.parent.profile.*
import dev.stmedrano.harbor.parent.ui.*
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ChildNotificationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun childControlsGateNativeProviderAndReportUnconfirmedBackend() {
        val binding = ChildBinding("00000000-0000-4000-8000-000000000001",
            "00000000-0000-4000-8000-000000000002", "00000000-0000-4000-8000-000000000003")
        val lease = ProfileLease(ProfileRole.CHILD, binding.deviceId, 1)
        var active: ProfileLease? = lease
        var permission = false
        var providerCalls = 0; var backendCalls = 0
        val stored = mutableMapOf<String, String>()
        val store = ChildNotificationStore(object : AuthValues {
            override fun read(key: String) = stored[key]
            override fun write(key: String, value: String?) { if (value == null) stored.remove(key) else stored[key] = value }
            override fun clear() { stored.clear() }
        })
        val provider = AndroidFirebaseTokenProvider({ providerCalls++; Tasks.forResult("ephemeral-native-token") },
            { Tasks.forResult<Void>(null) }, {})
        val router = ProfileNotificationRouter({ active }, { binding }, { store.optedBinding() == binding }, { permission },
            { _, _ -> true }, { false }, provider::token,
            { expected, token -> assertEquals(lease, expected); assertEquals("ephemeral-native-token", token); backendCalls++; false }, {})
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            val scope = rememberCoroutineScope()
            val status by router.state.collectAsState()
            ChildDashboard(ChildSyncState.Stale(null), binding, {}, {},
                notificationConfirmed = status.lease == lease && status.confirmed,
                onEnableNotifications = { scope.launch { store.setOpted(binding, true); router.syncToken(lease) } })
        } } }
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(0, providerCalls); assertEquals(0, backendCalls)
        compose.onNodeWithText("Backend notification registration is unconfirmed.").assertExists()
        compose.runOnIdle { permission = true }
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        compose.waitUntil(5000) { backendCalls == 1 && !router.state.value.busy }
        assertEquals(1, providerCalls)
        compose.onNodeWithText("Backend notification registration is unconfirmed.").assertExists()
        assertFalse(stored.values.joinToString().contains("ephemeral-native-token"))
        compose.runOnIdle { active = lease.copy(generation = 2) }
        compose.onNodeWithText("Enable notifications").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(1, providerCalls); assertEquals(1, backendCalls)
        compose.onNodeWithText("Settings").assertDoesNotExist()
        compose.onNodeWithText("Security").assertDoesNotExist()
    }
}
