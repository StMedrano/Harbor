package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.*
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.usage.*
import kotlinx.coroutines.delay

/** Builds the child's usage UI from local state only. Re-reads on resume and every few seconds while visible. */
@Composable
fun rememberChildUsageUi(graph: ParentApplication, lease: ProfileLease, resumeTick: Int, openSettings: () -> Unit): ChildUsageUi {
    var local by remember(lease) { mutableStateOf<LocalChildUsage?>(null) }
    var permission by remember(lease) { mutableStateOf(graph.isUsageAccessGranted()) }
    var tick by remember(lease) { mutableIntStateOf(0) }
    val runtime = graph.childUsageRuntime?.state?.collectAsState()?.value ?: UsageRuntimeStatus.DISABLED
    LaunchedEffect(lease, resumeTick, tick) {
        while (true) {
            local = graph.localChildUsage(lease)
            permission = graph.isUsageAccessGranted()
            delay(3000)
        }
    }
    // Sharing is on by default for a paired child phone (a parent turns it off by removing the device). Try once per phone profile;
    // the Start button stays as a fallback if this could not run.
    var autoStarted by remember(lease) { mutableStateOf(false) }
    val loaded = local
    LaunchedEffect(lease, loaded) {
        if (loaded != null && !autoStarted && !loaded.read.recovering && loaded.read.state?.consent != true) {
            autoStarted = true
            graph.setChildUsageSharing(lease, true)
            tick++
        }
    }
    return ChildUsageUi(
        view = graph.childUsageView(local, runtime),
        consent = local?.read?.state?.consent == true,
        permissionGranted = permission,
        onStart = { graph.setChildUsageSharing(lease, true); tick++ },
        onOpenSettings = openSettings,
        onCheckAgain = { graph.refreshChildUsage(lease); tick++ },
    )
}
