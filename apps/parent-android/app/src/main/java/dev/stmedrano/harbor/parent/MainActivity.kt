package dev.stmedrano.harbor.parent

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.stmedrano.harbor.parent.ui.HarborTheme
import dev.stmedrano.harbor.parent.ui.ParentApp
import dev.stmedrano.harbor.parent.ui.AuthScreen
import dev.stmedrano.harbor.parent.ui.FamilyRoute
import dev.stmedrano.harbor.parent.ui.SettingsScreen
import dev.stmedrano.harbor.parent.ui.DeviceScreen
import dev.stmedrano.harbor.parent.ui.SecurityScreen
import dev.stmedrano.harbor.parent.family.DevicePublicV1
import dev.stmedrano.harbor.parent.notifications.*

class MainActivity : ComponentActivity() {
    private val callback = mutableStateOf<String?>(null)
    private val accountPage = mutableStateOf(true)
    private val settingsPage = mutableStateOf(false)
    private val securityPage = mutableStateOf(false)
    private var requestedHint: ParentHint? = null
    private val logoutRequested = mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        val requested = scrubCallback(intent)
        requestedHint = ParentNotificationRenderer.consumeTap(intent)
        super.onCreate(savedInstanceState)
        callback.value = requested
        enableEdgeToEdge()
        setContent {
            val graph = application as ParentApplication
            val identity = graph.authRepository?.identity?.collectAsState()?.value
            val runtime = graph.runtime?.state?.collectAsState()?.value
            val notifications = graph.notifications?.state?.collectAsState()?.value ?: ParentNotificationState()
            val tap = graph.tapState.collectAsState().value
            val pendingExport = remember { mutableStateOf<Pair<ParentIdentity, ParentReceipt>?>(null) }
            val exportReceipt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                val captured = pendingExport.value
                pendingExport.value = null
                if (uri != null && captured != null) graph.accountScope.launch {
                    if (graph.authRepository?.identity?.value == captured.first && graph.runtime?.state?.value?.signingOut != true &&
                        graph.notifications?.state?.value?.receipt == captured.second) {
                        try { withContext(Dispatchers.IO) {
                            checkNotNull(contentResolver.openOutputStream(uri)).use { stream ->
                                stream.write(Json.encodeToString(listOf(captured.second)).toByteArray(Charsets.UTF_8))
                            }
                        } } catch (_: Exception) { /* No credentials or errors are logged. Retry from current evidence. */ }
                    }
                }
            }
            fun openRevocation(device: DevicePublicV1) {
                graph.securityViewModel?.requestRevocation(device.familyId, device.id)
                graph.closeNotification(); securityPage.value = true; accountPage.value = false; settingsPage.value = false
            }
            fun enableNotifications() { graph.accountScope.launch { graph.accountWork { graph.notifications?.enable() } } }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { enableNotifications() }
            HarborTheme {
                ParentApp {
                    if (runtime?.signingOut == true) Text("Signing out…")
                    else {
                        if (logoutRequested.value && identity == null) Text(if (runtime?.cleanupConfirmed == true) "Signed out. Current-device cleanup confirmed." else "Signed out locally. Remote cleanup is unconfirmed.")
                        if (identity != null) {
                            TextButton({ accountPage.value = !accountPage.value; settingsPage.value = false; securityPage.value = false; graph.closeNotification() }) { Text(if (accountPage.value) "Open family" else "Account") }
                            TextButton({ settingsPage.value = !settingsPage.value; securityPage.value = false; graph.closeNotification() }) { Text(if (settingsPage.value) "Back to family" else "Settings") }
                            TextButton({ securityPage.value = !securityPage.value; accountPage.value = false; settingsPage.value = false; graph.closeNotification() }) { Text(if (securityPage.value) "Back to family" else "Security") }
                        }
                        tap.message?.let { Text(it) }
                        val family = graph.familyViewModel?.repository?.state?.collectAsState()?.value
                        val snapshot = family?.snapshot
                        val visibleDevice = tap.device?.takeIf { identity != null && snapshot?.membership?.userId == identity.userId && snapshot.devices.any { device -> device == it } }
                        if (identity != null && securityPage.value) key(identity) { SecurityScreen(checkNotNull(graph.securityViewModel), onBack = { securityPage.value = false }, runtime = graph.runtime) }
                        else if (identity != null && visibleDevice != null) DeviceScreen(visibleDevice,
                            onRevoke = if (family != null && !family.cached && !family.loading && family.failure == null) { { openRevocation(visibleDevice) } } else null) { graph.closeNotification() }
                        else if (identity == null || (accountPage.value && !settingsPage.value) || callback.value != null) AuthScreen(graph.authRepository, callback.value, graph.runtime) { callback.value = null }
                        else if (settingsPage.value) SettingsScreen(notifications, runtime ?: ParentRuntimeState(), graph.notifications != null,
                            onEnable = {
                                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else enableNotifications()
                            },
                            onRemove = { graph.accountScope.launch { graph.accountWork { graph.notifications?.remove() } } },
                            onSignOut = { logoutRequested.value = true; graph.accountScope.launch { graph.runtime?.signOutCurrent() } },
                            developmentReceipt = if (BuildConfig.DEBUG && !BuildConfig.CI_FIXTURE) notifications.receipt else null,
                            onExport = { val receipt = notifications.receipt; if (identity != null && receipt != null) {
                                pendingExport.value = identity to receipt
                                exportReceipt.launch("harbor-parent-receipt.json")
                            } })
                        else key(identity) { FamilyRoute(checkNotNull(graph.familyViewModel), checkNotNull(identity), graph.runtime, ::openRevocation) }
                    }
                }
            }
        }
        requestedHint?.let { (application as ParentApplication).openNotification(it); accountPage.value = false }
        requestedHint = null
    }

    override fun onNewIntent(intent: Intent) {
        val requested = scrubCallback(intent)
        val hint = ParentNotificationRenderer.consumeTap(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        callback.value = requested
        accountPage.value = hint == null
        settingsPage.value = false
        securityPage.value = false
        hint?.let { (application as ParentApplication).openNotification(it) }
    }
    override fun onStart() { super.onStart(); (application as ParentApplication).foreground() }

    private fun scrubCallback(intent: Intent): String? {
        val requested = intent.dataString
        intent.data = null
        intent.clipData = null
        return if (intent.action == Intent.ACTION_VIEW) requested else null
    }
}
