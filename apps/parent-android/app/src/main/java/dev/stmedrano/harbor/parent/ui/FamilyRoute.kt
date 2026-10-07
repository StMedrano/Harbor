package dev.stmedrano.harbor.parent.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.ParentRuntime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

@Composable
fun FamilyRoute(model: FamilyViewModel, identity: ParentIdentity, runtime: ParentRuntime? = null, onRevoke: ((DevicePublicV1) -> Unit)? = null) {
    val family by model.repository.state.collectAsState()
    val control by model.state.collectAsState()
    val pairing by model.pairing.state.collectAsState()
    val scope = rememberCoroutineScope()
    var sheet by remember(identity) { mutableStateOf<String?>(null) }
    var device by remember(identity) { mutableStateOf<DevicePublicV1?>(null) }
    var name by remember(identity) { mutableStateOf("") }
    var error by remember(identity) { mutableStateOf<String?>(null) }
    var expired by remember(identity) { mutableStateOf(true) }

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
    BackHandler(sheet != null || device != null) { sheet = null; device = null }
    val canRevoke = !family.cached && !family.loading && family.failure == null && !control.busy
    if (device != null) DeviceScreen(device!!, onRevoke?.takeIf { canRevoke }?.let { action -> { action(device!!) } }) { device = null } else {
        FamilyScreen(family.copy(loading = family.loading || control.busy), control.families, control.selectedChildId,
            onRefresh = { submit { model.refresh(identity) } },
            onSelectFamily = { id -> submit { model.selectFamily(identity, id) } },
            onSelectChild = model::selectChild,
            onCreateFamily = { sheet = "family"; error = null },
            onAddChild = { sheet = "child"; error = null },
            onPair = { sheet = "pair"; submit { model.issuePairing(identity) } },
            onDevice = { device = it })
        if (control.message != null && sheet == null) Text(control.message!!)
    }
    when (sheet) {
        "family" -> AlertDialog(onDismissRequest = { if (!control.busy) sheet = null }, title = { Text("Create family") },
            text = { Column { OutlinedTextField(name, { name = it }, label = { Text("Family name") }, enabled = !control.busy); error?.let { Text(it) } } },
            confirmButton = { TextButton({ scope.launch { if (request { model.createFamily(identity, name) }) { sheet = null; name = "" } } }, enabled = !control.busy && name.isNotBlank()) { Text("Create / retry") } },
            dismissButton = { TextButton({ scope.launch { if (request { model.cancelFamily(identity) }) { sheet = null; name = "" } } }, enabled = !control.busy) { Text("Cancel request") } })
        "child" -> ChildSetupSheet(name, control.busy, error,
            onSubmit = { entered -> name = entered; scope.launch { if (request { model.submitChild(identity, entered) }) { sheet = null; name = "" } } },
            onCancel = { scope.launch { if (request { model.cancelChild(identity) }) { sheet = null; name = "" } } })
        "pair" -> PairingSheet(pairing, expired, error,
            onRenew = { submit { model.issuePairing(identity) } }, onCheck = { submit { model.refresh(identity) } }, onClose = { sheet = null })
    }
}
