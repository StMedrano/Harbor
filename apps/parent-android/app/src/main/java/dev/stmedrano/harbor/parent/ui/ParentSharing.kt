package dev.stmedrano.harbor.parent.ui

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.familymap.FamilyMapFix
import dev.stmedrano.harbor.parent.familymap.FamilyMapProblem
import dev.stmedrano.harbor.parent.familymap.ParentFamilyMapModel
import dev.stmedrano.harbor.parent.familymap.ParentFamilyMapState
import dev.stmedrano.harbor.parent.familymap.validFamilyMapLabel
import dev.stmedrano.harbor.parent.location.AndroidLocationSource
import dev.stmedrano.harbor.parent.location.LocationFix
import dev.stmedrano.harbor.parent.location.locationInstantMs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** What the parent's Family page needs for the two map switches. */
data class ParentSharingUi(
    val state: ParentFamilyMapState,
    val label: String,
    val onLabel: (String) -> Unit,
    /** Set when the phone would not give a place (permission refused, location off, or none found). */
    val placeProblem: String? = null,
    val onShareChange: (Boolean) -> Unit,
    val onChildrenCanViewChange: (Boolean) -> Unit,
    val onRetry: () -> Unit,
)

internal const val DEFAULT_PARENT_LABEL = "Parent"
/** A place older than this is not sent; it matches the server's ten-minute limit with a minute to spare. */
private const val SHARE_MAX_FIX_AGE_MS = 9 * 60_000L
private const val SHARE_FIRST_FIX_TIMEOUT_MS = 20_000L
private const val SHARE_MIN_GAP_MS = 60_000L

@Composable
fun ParentSharingPanel(ui: ParentSharingUi) {
    val state = ui.state
    HarborPanel("Family map") {
        when {
            !state.loaded && state.problem == FamilyMapProblem.COULD_NOT_LOAD -> {
                Text("Could not load the map settings.")
                TextButton(ui.onRetry) { Text("Try again") }
            }
            !state.loaded -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val sharing = state.mySharing != null
                HarborRow("Share my location", subtitle = if (sharing) "Sharing as ${state.mySharing?.label}" else "Your family sees it while Harbor is open",
                    trailing = { HarborSwitch(sharing, ui.onShareChange, Modifier.semantics { contentDescription = "Share my location" },
                        enabled = !state.busy && (sharing || validFamilyMapLabel(ui.label))) })
                if (!sharing) HarborField(ui.label, ui.onLabel, "Name on the map",
                    error = if (!validFamilyMapLabel(ui.label)) "Use 1 to 40 characters." else null, hint = "How your family sees you on the map.")
                HarborRow("Children can see the family map", divider = true,
                    trailing = { HarborSwitch(state.childrenCanView, ui.onChildrenCanViewChange, Modifier.semantics { contentDescription = "Children can see the family map" }, enabled = !state.busy) })
                ui.placeProblem?.let { HarborNote(it, HarborTone.WARN) }
                when (state.problem) {
                    FamilyMapProblem.COULD_NOT_SAVE -> HarborNote("Could not save that change. Try again.", HarborTone.DANGER)
                    FamilyMapProblem.COULD_NOT_SHARE -> HarborNote("Could not update your place. Try again.", HarborTone.DANGER)
                    else -> Unit
                }
            }
        }
    }
}

private fun Context.activity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is ComponentActivity) return c; c = c.baseContext }
    return null
}

/** The phone's newest place, or the first one to arrive within the timeout. Null when none is available. */
private suspend fun AndroidLocationSource.currentPlace(now: () -> Long): LocationFix? {
    lastKnown()?.takeIf { now() - locationInstantMs(it.at) <= SHARE_MAX_FIX_AGE_MS }?.let { return it }
    return try {
        withTimeoutOrNull(SHARE_FIRST_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine<LocationFix> { cont ->
                if (!start { fix -> if (cont.isActive) cont.resume(fix) }) cont.cancel()
                cont.invokeOnCancellation { stop() }
            }
        }
    } finally { stop() }
}

private fun LocationFix.toMapFix() = FamilyMapFix(at, latitude, longitude, accuracyM)

/**
 * Loads the two switches and, while this parent is sharing and Harbor is on screen, keeps their latest place up to date.
 * Nothing runs once Harbor leaves the screen, and there is no background service.
 */
@Composable
fun rememberParentSharing(model: ParentFamilyMapModel?, identity: ParentIdentity, familyId: String?, resumeTick: Int): ParentSharingUi? {
    if (model == null || familyId == null) return null
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = model.state.collectAsState().value
    val shown = if (state.familyId == familyId) state else ParentFamilyMapState(familyId)
    var label by remember(identity, familyId) { mutableStateOf(DEFAULT_PARENT_LABEL) }
    var placeProblem by remember(identity, familyId) { mutableStateOf<String?>(null) }
    var foreground by remember { mutableStateOf(true) }
    val source = remember { AndroidLocationSource(context) }
    val now = { System.currentTimeMillis() }

    LaunchedEffect(model, identity, familyId, resumeTick) { model.load(identity, familyId) }
    LaunchedEffect(shown.mySharing?.label) { shown.mySharing?.label?.let { label = it } }
    DisposableEffect(context) {
        val owner = context.activity()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) foreground = true else if (event == Lifecycle.Event.ON_STOP) foreground = false
        }
        owner?.lifecycle?.addObserver(observer)
        onDispose { owner?.lifecycle?.removeObserver(observer) }
    }

    suspend fun startSharing() {
        placeProblem = null
        val access = source.access()
        if (!access.fineGranted) { placeProblem = "Allow location for Harbor in your phone's settings to share."; return }
        if (!access.servicesOn) { placeProblem = "Turn on location in your phone's settings to share."; return }
        val place = source.currentPlace(now)
        if (place == null) { placeProblem = "Could not find your place yet. Try again in a moment."; return }
        model.share(identity, familyId, label.trim(), place.toMapFix(), interactive = true)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants[Manifest.permission.ACCESS_FINE_LOCATION] == true) scope.launch { startSharing() }
        else placeProblem = "Allow location for Harbor in your phone's settings to share."
    }

    // While sharing and on screen: send the newest place, then each new one at most once a minute.
    val sharing = shown.mySharing != null
    val publisher = remember { AndroidLocationSource(context) }
    LaunchedEffect(sharing, foreground, shown.mySharing?.label, identity, familyId) {
        val name = shown.mySharing?.label
        if (!sharing || !foreground || name == null || !publisher.access().fineGranted) return@LaunchedEffect
        var lastSent = 0L
        val send: suspend (LocationFix) -> Unit = { fix ->
            val at = now()
            if (at - lastSent >= SHARE_MIN_GAP_MS && at - locationInstantMs(fix.at) <= SHARE_MAX_FIX_AGE_MS) {
                lastSent = at
                model.share(identity, familyId, name, fix.toMapFix(), interactive = false)
            }
        }
        publisher.lastKnown()?.let { send(it) }
        val incoming = kotlinx.coroutines.channels.Channel<LocationFix>(kotlinx.coroutines.channels.Channel.CONFLATED)
        try {
            publisher.start { incoming.trySend(it) }
            while (true) { send(incoming.receive()); delay(1_000) }
        } finally { publisher.stop(); incoming.close() }
    }
    DisposableEffect(Unit) { onDispose { source.stop(); publisher.stop() } }

    return ParentSharingUi(shown, label, { label = it.take(40) }, placeProblem,
        onShareChange = { on ->
            if (!on) { placeProblem = null; scope.launch { model.stop(identity, familyId) } }
            else if (!source.access().fineGranted) permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            else scope.launch { startSharing() }
        },
        onChildrenCanViewChange = { value -> scope.launch { model.setChildrenCanView(identity, familyId, value) } },
        onRetry = { scope.launch { model.load(identity, familyId) } })
}
