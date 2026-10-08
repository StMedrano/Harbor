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
import androidx.compose.runtime.LaunchedEffect
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
import dev.stmedrano.harbor.parent.ui.ParentSessionContent
import dev.stmedrano.harbor.parent.ui.AuthScreen
import dev.stmedrano.harbor.parent.ui.FamilyRoute
import dev.stmedrano.harbor.parent.ui.SettingsScreen
import dev.stmedrano.harbor.parent.ui.DeviceScreen
import dev.stmedrano.harbor.parent.ui.SecurityScreen
import dev.stmedrano.harbor.parent.family.DevicePublicV1
import dev.stmedrano.harbor.parent.notifications.*
import dev.stmedrano.harbor.parent.profile.ProfileState
import dev.stmedrano.harbor.parent.profile.ProfileRole
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.ui.FamilyEntryState
import dev.stmedrano.harbor.parent.ui.FamilyDestination
import dev.stmedrano.harbor.parent.ui.FamilyRoleContent
import dev.stmedrano.harbor.parent.ui.FamilyEntryScreen
import dev.stmedrano.harbor.parent.ui.ChildDashboard
import dev.stmedrano.harbor.parent.ui.ChildPairingScreen
import dev.stmedrano.harbor.parent.ui.ParentApprovalScreen
import dev.stmedrano.harbor.parent.ui.ProtectSensitiveScreen
import androidx.compose.material3.AlertDialog

class MainActivity : ComponentActivity() {
    private val callback = mutableStateOf<String?>(null)
    private val authenticationPage = mutableStateOf(true)
    private val settingsPage = mutableStateOf(false)
    private val securityPage = mutableStateOf(false)
    private var requestedHint: ParentHint? = null
    private var requestedChildHint: ParentRoute? = null
    private val logoutRequested = mutableStateOf(false)
    private val setupRole = mutableStateOf<ProfileRole?>(null)
    private val roleChangeParentLease = mutableStateOf<ProfileLease?>(null)
    private val transitionMessage = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        val requested = scrubCallback(intent)
        requestedChildHint = if (intent.action == ChildNotificationRenderer.TAP_ACTION) ChildNotificationRenderer.consumeTap(intent) else null
        requestedHint = ParentNotificationRenderer.consumeTap(intent)
        super.onCreate(savedInstanceState)
        callback.value = requested
        enableEdgeToEdge()
        setContent {
            val graph = application as ParentApplication
            val profile = graph.profiles.state.collectAsState().value
            val approval = graph.approvalSession.collectAsState().value
            val approvalClosing = graph.approvalClosing.collectAsState().value
            LaunchedEffect(profile, callback.value) {
                if (profile is ProfileState.Parent || profile is ProfileState.Child) setupRole.value = null
                // This limited approval flow creates no email transaction. Unsolicited links cannot import a primary session.
                if (profile is ProfileState.Child) callback.value = null
            }
            if (approval != null) {
                HarborTheme { ParentApp(showBrand = false) {
                    if (approvalClosing) { ProtectSensitiveScreen(); Text("Closing parent approval…") } else key(approval) {
                        ParentApprovalScreen(approval::signIn, approval::verify,
                            { graph.removeChildEnrollment(approval) },
                            { graph.accountScope.launch { graph.cancelChildApproval() } })
                    }
                } }
                return@setContent
            }
            val selected = setupRole.value ?: ProfileRole.CHILD.takeIf { profile == ProfileState.Setup && graph.hasChildSetup() }
            val child = if (profile is ProfileState.Child || selected == ProfileRole.CHILD) graph.childForSetup() else null
            val childState = child?.state?.collectAsState()?.value
            val childBinding = child?.binding?.collectAsState()?.value
            val entryState = FamilyEntryState(profile, selected, childBinding = childBinding, childState = childState)
            if (entryState.destination() !in setOf(FamilyDestination.PARENT_AUTH, FamilyDestination.PARENT_HOME)) {
                val childPermissionRequest = remember { mutableStateOf<ProfileLease?>(null) }
                val childPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    val expected = childPermissionRequest.value
                    childPermissionRequest.value = null
                    if (expected != null && graph.profiles.isCurrent(expected)) graph.enableChildNotifications(expected)
                }
                HarborTheme { ParentApp(showBrand = false) {
                    transitionMessage.value?.let { Text(it) }
                    FamilyRoleContent(entryState,
                        entry = { FamilyEntryScreen(
                            onParent = { if (graph.openParentSetup()) setupRole.value = ProfileRole.PARENT },
                            onChild = { if (graph.childForSetup() != null) setupRole.value = ProfileRole.CHILD }) },
                        parentAuth = {}, parentMenu = {}, parentContent = {},
                        childPairing = {
                            Text("Enter pairing code")
                            if (child != null) ChildPairingScreen(child) {
                                graph.accountScope.launch { graph.profiles.activateChild() }
                            } else Text("This phone needs a parent to check its setup.")
                        },
                        childContent = {
                            val lease = (profile as ProfileState.Child).lease
                            val registration = graph.profileNotifications.state.collectAsState().value
                            ChildDashboard(checkNotNull(childState), checkNotNull(childBinding),
                                onSync = { graph.syncChild(lease, childBinding) },
                                onRequestRoleChange = {
                                    transitionMessage.value = null
                                    graph.accountScope.launch {
                                        try {
                                            if (!graph.beginChildApproval(lease)) transitionMessage.value = "Parent approval is unavailable. This phone remains enrolled."
                                        } catch (_: Exception) { transitionMessage.value = "Parent approval could not start. This phone remains enrolled." }
                                    }
                                },
                                notificationConfirmed = registration.lease == lease && registration.confirmed,
                                onEnableNotifications = {
                                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                        childPermissionRequest.value = lease
                                        childPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else graph.enableChildNotifications(lease)
                                })
                        })
                } }
                return@setContent
            }
            val identity = graph.authRepository?.identity?.collectAsState()?.value
            roleChangeParentLease.value?.let { expected ->
                HarborTheme { AlertDialog(onDismissRequest = { roleChangeParentLease.value = null },
                    title = { Text("Change this phone's role?") },
                    text = { Text("Harbor must confirm current-device sign-out and cleanup before choosing another role.") },
                    confirmButton = { TextButton(onClick = {
                        roleChangeParentLease.value = null
                        graph.accountScope.launch { graph.parentToSetup(expected) }
                    }) { Text("Confirm and sign out") } },
                    dismissButton = { TextButton(onClick = { roleChangeParentLease.value = null }) { Text("Cancel") } }) }
            }
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
                graph.closeNotification(); securityPage.value = true; authenticationPage.value = false; settingsPage.value = false
            }
            fun enableNotifications(expected: ProfileLease?) { graph.accountScope.launch {
                if (expected != null && graph.profiles.isCurrent(expected)) graph.accountWork { graph.notifications?.enable() }
            } }
            val parentPermissionRequest = remember { mutableStateOf<ProfileLease?>(null) }
            val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                val expected = parentPermissionRequest.value; parentPermissionRequest.value = null
                enableNotifications(expected)
            }
            val authenticationOpen = authenticationPage.value || callback.value != null
            HarborTheme {
                ParentApp(showBrand = identity != null && !authenticationOpen) {
                    if (runtime?.signingOut == true) Text("Signing out…")
                    else {
                        if (logoutRequested.value && identity == null) Text(if (runtime?.cleanupConfirmed == true) "Signed out. Current-device cleanup confirmed." else "Signed out locally. Remote cleanup is unconfirmed.")
                        ParentSessionContent(identity?.takeIf { profile is ProfileState.Parent && profile.lease.ownerId == it.userId }, authenticationOpen,
                            authentication = {
                                AuthScreen(graph.authRepository, callback.value, graph.runtime,
                                    onAuthenticated = {
                                        graph.accountScope.launch {
                                        graph.profiles.activateParent()
                                        if (graph.profiles.state.value is ProfileState.Parent) withContext(Dispatchers.Main) {
                                        authenticationPage.value = false; settingsPage.value = false; securityPage.value = false
                                        logoutRequested.value = false; graph.closeNotification()
                                        }
                                        }
                                    }, onCallbackConsumed = { callback.value = null })
                            },
                            parentMenu = {
                                TextButton({ authenticationPage.value = false; settingsPage.value = false; securityPage.value = false; graph.closeNotification() }) { Text("Family") }
                                TextButton({ settingsPage.value = !settingsPage.value; securityPage.value = false; graph.closeNotification() }) { Text("Settings") }
                                TextButton({ securityPage.value = !securityPage.value; settingsPage.value = false; graph.closeNotification() }) { Text("Security") }
                            },
                            parentContent = {
                                tap.message?.let { Text(it) }
                                val family = graph.familyViewModel?.repository?.state?.collectAsState()?.value
                                val snapshot = family?.snapshot
                                val visibleDevice = tap.device?.let { reference -> snapshot?.takeIf {
                                    identity != null && it.membership.userId == identity.userId && it.family.id == reference.familyId
                                }?.devices?.firstOrNull { it.id == reference.id } }
                                if (identity != null && securityPage.value) key(identity) { SecurityScreen(checkNotNull(graph.securityViewModel), onBack = { securityPage.value = false }, runtime = graph.runtime) }
                                else if (identity != null && visibleDevice != null) DeviceScreen(visibleDevice,
                                    onRevoke = if (family != null && !family.cached && !family.loading && family.failure == null) { { openRevocation(visibleDevice) } } else null,
                                    cachedAt = snapshot?.fetchedAt?.takeIf { family?.cached == true }) { graph.closeNotification() }
                                else if (settingsPage.value) SettingsScreen(notifications, runtime ?: ParentRuntimeState(), graph.notifications != null,
                                    onEnable = {
                                        val expected = graph.profiles.currentLease()
                                        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                            parentPermissionRequest.value = expected; permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else enableNotifications(expected)
                                    },
                                    onRemove = { graph.accountScope.launch { graph.accountWork { graph.notifications?.remove() } } },
                                    onSignOut = { logoutRequested.value = true; graph.accountScope.launch { graph.runtime?.signOutCurrent() } },
                                    developmentReceipt = if (BuildConfig.DEBUG && !BuildConfig.CI_FIXTURE) notifications.receipt else null,
                                    onExport = { val receipt = notifications.receipt; if (identity != null && receipt != null) {
                                        pendingExport.value = identity to receipt
                                        exportReceipt.launch("harbor-parent-receipt.json")
                                    } }, onChangeRole = { roleChangeParentLease.value = graph.profiles.currentLease() })
                                else key(identity) { FamilyRoute(checkNotNull(graph.familyViewModel), checkNotNull(identity), graph.runtime, ::openRevocation) }
                            })
                    }
                }
            }
        }
        requestedHint?.let { (application as ParentApplication).openNotification(it); authenticationPage.value = false }
        requestedHint = null
        requestedChildHint?.let { (application as ParentApplication).openChildNotification(it) }
        requestedChildHint = null
    }

    override fun onNewIntent(intent: Intent) {
        val requested = scrubCallback(intent)
        val childHint = if (intent.action == ChildNotificationRenderer.TAP_ACTION) ChildNotificationRenderer.consumeTap(intent) else null
        val hint = ParentNotificationRenderer.consumeTap(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        callback.value = requested
        authenticationPage.value = hint == null
        settingsPage.value = false
        securityPage.value = false
        hint?.let { (application as ParentApplication).openNotification(it) }
        childHint?.let { (application as ParentApplication).openChildNotification(it) }
    }
    override fun onStart() { super.onStart(); (application as ParentApplication).foreground() }

    private fun scrubCallback(intent: Intent): String? {
        val requested = intent.dataString
        intent.data = null
        intent.clipData = null
        return if (intent.action == Intent.ACTION_VIEW) requested else null
    }
}

