package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.*
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.usage.*

/** Loads the parent's read-only usage for one device and returns the view valid for exactly this parent and device. */
@Composable
fun rememberParentUsageView(model: ParentUsageModel?, identity: ParentIdentity, deviceId: String, resumeTick: Int = 0): UsageViewState? {
    if (model == null) return null
    LaunchedEffect(model, identity, deviceId, resumeTick) { model.load(identity, deviceId) }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentUsageScope(identity, deviceId) }?.view
}
