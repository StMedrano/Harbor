package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildSyncState

@Composable fun ChildDashboard(state: ChildSyncState, binding: ChildBinding, onSync: () -> Unit, onRequestRoleChange: () -> Unit,
    notificationConfirmed: Boolean = false, onEnableNotifications: (() -> Unit)? = null, usage: ChildUsageUi? = null,
    /** When set, the caller owns tab selection and shows the tabs as a pinned bottom bar (see [childNavItems]). */
    tab: String? = null) {
    if (state is ChildSyncState.Blocked) {
        Text("This phone needs a parent to check its setup.")
        return
    }
    var localTab by remember(binding.deviceId) { mutableStateOf("Today") }
    val current = tab ?: localTab
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your phone", style = MaterialTheme.typography.headlineSmall)
        if (tab == null) HarborTabs(CHILD_TABS.map { title -> HarborTab(title, current == title) { localTab = title } })
        when (current) {
            "Today" -> {
                HarborCard {
                    Text("Connected to your family", style = MaterialTheme.typography.titleMedium)
                    when (state) {
                        is ChildSyncState.Fresh -> {
                            Text("Signed sync confirmed. State version: ${state.desiredVersion}.")
                            Text("Last sync: ${formatTimestamp(java.time.Instant.ofEpochSecond(state.receivedAt).toString())}")
                        }
                        is ChildSyncState.Stale -> {
                            Text("Showing saved setup. Current status has not been confirmed.")
                            state.lastSuccessAt?.let { Text("Last confirmed sync: ${formatTimestamp(java.time.Instant.ofEpochSecond(it).toString())}") }
                        }
                        is ChildSyncState.Blocked -> Unit
                    }
                }
                HarborButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
                if (usage != null) {
                    UsageSetupPanel(usage)
                    UsageTodayPanel(usage.view)
                }
            }
            "Apps" -> if (usage != null) UsageAppsPanel(usage.view) else Text("App information is not available yet.")
            "About" -> {
                HarborCard {
                    Text("Harbor stores this phone’s pairing credentials securely and checks its family connection with signed requests.")
                    if (usage != null) Text("Harbor measures screen time only while sharing is on. It does not limit or block apps.")
                    else Text("Screen time and app enforcement are not available in this build.")
                }
                if (onEnableNotifications != null) {
                    HarborCard {
                        Text(if (notificationConfirmed) "Backend notification registration confirmed. Delivery still requires receipt evidence."
                            else "Backend notification registration is unconfirmed.")
                    }
                    HarborButton(onClick = onEnableNotifications, modifier = Modifier.fillMaxWidth()) { Text("Enable notifications") }
                }
                HarborOutlinedButton(onClick = onRequestRoleChange, modifier = Modifier.fillMaxWidth()) { Text("Ask a parent to change setup") }
            }
        }
    }
}

val CHILD_TABS = listOf("Today", "Apps", "About")

fun childNavItems(selected: String, onSelect: (String) -> Unit) = listOf(
    HarborNavItem("Today", HarborGlyph.TODAY, selected == "Today") { onSelect("Today") },
    HarborNavItem("Apps", HarborGlyph.APPS, selected == "Apps") { onSelect("Apps") },
    HarborNavItem("About", HarborGlyph.ABOUT, selected == "About") { onSelect("About") },
)
