package dev.stmedrano.harbor.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.ParentRuntime
import dev.stmedrano.harbor.parent.usage.ParentUsageModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

@Composable
fun FamilyRoute(model: FamilyViewModel, identity: ParentIdentity, runtime: ParentRuntime? = null, onRevoke: ((DevicePublicV1) -> Unit)? = null,
    usageModel: ParentUsageModel? = null, locationModel: dev.stmedrano.harbor.parent.location.ParentLocationModel? = null,
    alertsModel: dev.stmedrano.harbor.parent.alerts.ParentAlertsModel? = null,
    checkInModel: dev.stmedrano.harbor.parent.checkin.ParentCheckInModel? = null,
    familyMapModel: dev.stmedrano.harbor.parent.familymap.ParentFamilyMapModel? = null,
    controlsModel: dev.stmedrano.harbor.parent.controls.ParentControlsModel? = null,
    activityModel: dev.stmedrano.harbor.parent.activity.ParentActivityModel? = null,
    accessModel: dev.stmedrano.harbor.parent.access.ParentAccessModel? = null,
    tab: ParentTab = ParentTab.FAMILY, resumeTick: Int = 0,
    onSettings: (() -> Unit)? = null, onSecurity: (() -> Unit)? = null) {
    val family by model.repository.state.collectAsState()
    val control by model.state.collectAsState()
    val pairing by model.pairing.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Runs on every parent page so a parent who chose to share keeps sharing while Harbor is open.
    val sharing = rememberParentSharing(familyMapModel, identity, family.snapshot?.takeIf { !family.cached }?.family?.id, resumeTick)
    var sheet by remember(identity) { mutableStateOf<String?>(null) }
    var deviceId by remember(identity) { mutableStateOf<String?>(null) }
    var name by remember(identity) { mutableStateOf("") }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var expired by remember(identity) { mutableStateOf(true) }
    var legalDoc by remember(identity) { mutableStateOf<dev.stmedrano.harbor.parent.legal.LegalDoc?>(null) }

    suspend fun request(action: suspend () -> Unit): Boolean = try {
        error = null
        withContext(Dispatchers.IO) { if (runtime == null) action() else runtime.authAction { action() } }
        true
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { error = "Request failed. Keep the same name to retry, or cancel the request."; false }
    fun submit(action: suspend () -> Unit) { if (!control.busy) scope.launch { request(action) } }

    LaunchedEffect(model, identity) { request { model.load(identity) } }
    LaunchedEffect(pairing.code) {
        while (pairing.code != null) { expired = model.pairing.expired(); delay(1000) }
    }
    // Picking another bottom tab leaves any open device page or legal document, so the page matches the selected tab.
    LaunchedEffect(tab) { deviceId = null; legalDoc = null }
    BackHandler(sheet != null || deviceId != null || legalDoc != null) { sheet = null; deviceId = null; legalDoc = null }
    val canRevoke = !family.cached && !family.loading && family.failure == null && !control.busy
    val device = family.snapshot?.takeIf { it.membership.userId == identity.userId && it.membership.status == "active" }
        ?.devices?.firstOrNull { it.id == deviceId && it.status != "revoked" }
    // A revoked phone is gone from the parent's view: close its page instead of showing a dead device.
    val openDeviceRevoked = deviceId != null && family.snapshot?.devices?.any { it.id == deviceId && it.status == "revoked" } == true
    LaunchedEffect(openDeviceRevoked) { if (openDeviceRevoked) deviceId = null }
    if (deviceId != null) {
        if (device != null) {
            DeviceScreen(device, onRevoke?.takeIf { canRevoke }?.let { action -> { action(device) } },
                cachedAt = family.snapshot?.fetchedAt?.takeIf { family.cached }) { deviceId = null }
        } else Column {
            Text("This device is unavailable. Refresh your family.")
            TextButton({ deviceId = null }) { Text("Back to family") }
        }
    } else if (legalDoc != null) {
        LegalScreen(checkNotNull(legalDoc)) { legalDoc = null }
    } else if (tab != ParentTab.FAMILY && family.snapshot != null) {
        val snapshot = checkNotNull(family.snapshot)
        val online = !family.cached && family.failure == null && !family.loading && !control.busy
        val index = snapshot.children.indexOfFirst { it.id == control.selectedChildId }
        val child = snapshot.children.getOrNull(index)
        KidSwitcher(snapshot.children, control.selectedChildId, model::selectChild, { sheet = "child"; error = null }, addEnabled = online)
        if (family.cached) HarborNote("Cached view · last refreshed ${java.time.Instant.ofEpochMilli(snapshot.fetchedAt)}. Connect to make changes.", HarborTone.WARN)
        if (child == null) {
            ParentTabHeader(tab, subtitle = snapshot.family.name)
            HarborCard {
                HarborEmptyState("No children yet", art = HarborArt.ANCHOR, body = "Add a child, then pair their phone.",
                    action = "Add child", onAction = { sheet = "child"; error = null }, actionEnabled = online)
            }
        } else {
            val phone = activeDevice(snapshot.devices, child.id)
            val today = remember(resumeTick) { java.time.LocalDate.now() }
            var activityDay by remember(phone?.id) { mutableStateOf(today) }
            // Each page reads only what it shows, so an idle tab makes no requests.
            val data = if (phone == null) ParentChildData() else ParentChildData(
                location = if (tab == ParentTab.MAP) rememberParentLocationView(locationModel, identity, phone.id, resumeTick) else null,
                onRefreshLocation = locationModel?.let { m -> { scope.launch { m.load(identity, phone.id) } } },
                alerts = if (tab == ParentTab.ALERTS) rememberParentAlertsView(alertsModel, identity, phone.id, resumeTick) else null,
                onRefreshAlerts = alertsModel?.let { m -> { scope.launch { m.load(identity, phone.id) } } },
                usage = if (tab == ParentTab.ACTIVITY || tab == ParentTab.CONTROLS) rememberParentUsageView(usageModel, identity, phone.id, resumeTick) else null,
                onRefreshUsage = usageModel?.let { m -> { scope.launch { m.load(identity, phone.id) } } },
                checkIn = if (tab == ParentTab.MAP) rememberParentCheckInView(checkInModel, identity, phone.id, resumeTick) else null,
                controls = if (tab == ParentTab.CONTROLS) rememberParentControlsView(controlsModel, identity, phone.id, resumeTick) else null,
                onChangeControls = controlsModel?.takeIf { online }?.let { m -> { change -> scope.launch { m.update(identity, phone.familyId, phone.id, change) } } },
                accessRequests = if (tab == ParentTab.CONTROLS) rememberParentAccessView(accessModel, identity, phone.id, resumeTick) else null,
                onAnswerAccess = accessModel?.takeIf { online && controlsModel != null }?.let { m -> { item, approve -> scope.launch {
                    // Yes = a 15-minute grant for that app, written to the phone's controls before the answer is recorded.
                    m.answer(identity, phone.id, item, approve) { request ->
                        val now = System.currentTimeMillis()
                        controlsModel?.update(identity, phone.familyId, phone.id) {
                            withGrant(it, request.packageName, now + dev.stmedrano.harbor.parent.access.ACCESS_GRANT_MS, now)
                        } == true
                    }
                } } },
                onRefreshControls = controlsModel?.let { m -> { scope.launch { m.load(identity, phone.id) } } },
                activity = if (tab == ParentTab.ACTIVITY || tab == ParentTab.CONTROLS) rememberParentActivityView(activityModel, identity, phone.id, activityDay, resumeTick) else null,
                activityToday = today, onActivityDay = { activityDay = it },
                onRefreshActivity = activityModel?.let { m -> { scope.launch { m.load(identity, phone.id, activityDay) } } },
                onAskCheckIn = checkInModel?.takeIf { online }?.let { m -> { scope.launch { m.ask(identity, phone.id) } } })
            ParentChildPage(tab, child, phone, data, HarborKid.color(index), onPair = if (online) ({ sheet = "pair"; submit { model.issuePairing(identity) } }) else null)
        }
    } else {
        FamilyScreen(family.copy(loading = family.loading || control.busy), control.families, control.selectedChildId,
            onRefresh = { submit { model.refresh(identity) } },
            onSelectFamily = { id -> submit { model.selectFamily(identity, id) } },
            onSelectChild = model::selectChild,
            onCreateFamily = { sheet = "family"; error = null },
            onAddChild = { sheet = "child"; error = null },
            onPair = { sheet = "pair"; submit { model.issuePairing(identity) } },
            onDevice = { deviceId = it.id },
            onSettings = onSettings, onSecurity = onSecurity, onOpenDoc = { legalDoc = it }, sharing = sharing)
        if (control.message != null && sheet == null) Text(control.message!!)
    }
    when (sheet) {
        "family" -> AlertDialog(onDismissRequest = { if (!control.busy) sheet = null }, title = { Text("Create family", style = MaterialTheme.typography.titleMedium) },
            text = { Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Family name") }, enabled = !control.busy, shape = MaterialTheme.shapes.medium, colors = harborFieldColors())
                error?.let { HarborNote(it, HarborTone.DANGER, emphasis = true) } } },
            confirmButton = { TextButton({ scope.launch { if (request { model.createFamily(identity, name) }) { sheet = null; name = "" } } }, enabled = !control.busy && name.isNotBlank()) { Text("Create / retry") } },
            dismissButton = { TextButton({ scope.launch { if (request { model.cancelFamily(identity) }) { sheet = null; name = "" } } }, enabled = !control.busy) { Text("Cancel request") } })
        "child" -> ChildSetupSheet(name, control.busy, error,
            onSubmit = { entered -> name = entered; scope.launch { if (request { model.submitChild(identity, entered) }) { sheet = null; name = "" } } },
            onCancel = { scope.launch { if (request { model.cancelChild(identity) }) { sheet = null; name = "" } } })
        "pair" -> PairingSheet(pairing, expired, error,
            onRenew = { submit { model.issuePairing(identity) } }, onCheck = { submit { model.refresh(identity) } }, onClose = { sheet = null })
    }
}
