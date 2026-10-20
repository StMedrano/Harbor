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
import dev.stmedrano.harbor.parent.legal.LegalDoc

@Composable fun ChildDashboard(state: ChildSyncState, binding: ChildBinding, onSync: () -> Unit, onRequestRoleChange: () -> Unit,
    notificationConfirmed: Boolean = false, onEnableNotifications: (() -> Unit)? = null, usage: ChildUsageUi? = null,
    location: ChildLocationUi? = null, alerts: ChildAlertsUi? = null, checkIn: ChildCheckInUi? = null, familyMap: ChildFamilyMapUi? = null,
    /** When set, the caller owns tab selection and shows the tabs as a pinned bottom bar (see [childNavItems]). */
    tab: String? = null, onSelectTab: ((String) -> Unit)? = null) {
    if (state is ChildSyncState.Blocked) {
        HarborNote("This phone needs a parent to check its setup.", HarborTone.WARN, emphasis = true)
        return
    }
    var localTab by remember(binding.deviceId) { mutableStateOf("Today") }
    var legalDoc by remember(binding.deviceId) { mutableStateOf<LegalDoc?>(null) }
    val openDoc = legalDoc
    if (openDoc != null) { LegalScreen(openDoc) { legalDoc = null }; return }
    val current = tab ?: localTab
    val selectTab: (String) -> Unit = { name -> if (onSelectTab != null) { onSelectTab(name) } else { localTab = name } }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (current == "Today") ChildGreeting(state, familyMap) else Text("Your phone", style = MaterialTheme.typography.headlineSmall)
        if (tab == null) HarborTabs(childTabs(checkIn != null, familyMap != null).map { title -> HarborTab(title, current == title) { localTab = title } })
        when (current) {
            "Today" -> {
                if (state is ChildSyncState.Stale) {
                    HarborNote("Showing saved setup. Current status has not been confirmed.", HarborTone.WARN)
                    state.lastSuccessAt?.let { Text("Last confirmed sync: ${formatTimestamp(java.time.Instant.ofEpochSecond(it).toString())}", style = MaterialTheme.typography.bodySmall) }
                }
                checkIn?.state?.pending?.let { pending -> CheckInRequestBanner(pending) { selectTab(CHECK_IN_TAB) } }
                if (familyMap != null) ChildPlaceRow(familyMap) { selectTab(FAMILY_MAP_TAB) }
                if (location != null || alerts != null || usage != null) {
                    HarborPanel("On this phone") {
                        val sections = buildList<@Composable () -> Unit> {
                            if (location != null) add { LocationStatusSection(location) }
                            if (alerts != null) add { MessageAlertsSection(alerts) }
                            if (usage != null) add { UsageStatusSection(usage) }
                        }
                        sections.forEachIndexed { index, section ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                            section()
                        }
                        TextButton({ selectTab("About") }) { Text("What Harbor shares and why") }
                    }
                }
                if (usage != null) UsageTodayPanel(usage.view)
                HarborOutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
            }
            CHECK_IN_TAB -> if (checkIn != null) CheckInScreen(checkIn) else Text("Check-ins are starting. Try again in a moment.")
            FAMILY_MAP_TAB -> if (familyMap != null) FamilyMapScreen(familyMap) else Text("The family map is starting. Try again in a moment.")
            "Apps" -> if (usage != null) UsageAppsPanel(usage.view) else Text("App information is not available yet.")
            "About" -> {
                LegalBody(rememberLegalBlocks(LegalDoc.CHILD_ABOUT))
                PolicyLinks { legalDoc = it }
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

const val CHECK_IN_TAB = "Check in"
const val FAMILY_MAP_TAB = "Family"
val CHILD_TABS = listOf("Today", "Apps", "About")
/** The Check in tab exists only where check-ins are available, so builds without them keep the original three. */
fun childTabs(withCheckIn: Boolean, withFamily: Boolean = false) = buildList {
    add("Today")
    if (withCheckIn) add(CHECK_IN_TAB)
    if (withFamily) add(FAMILY_MAP_TAB)
    add("Apps"); add("About")
}

fun childNavItems(selected: String, withCheckIn: Boolean = false, withFamily: Boolean = false, onSelect: (String) -> Unit) = buildList {
    add(HarborNavItem("Today", HarborGlyph.TODAY, selected == "Today") { onSelect("Today") })
    if (withCheckIn) add(HarborNavItem(CHECK_IN_TAB, HarborGlyph.CHECK_IN, selected == CHECK_IN_TAB) { onSelect(CHECK_IN_TAB) })
    if (withFamily) add(HarborNavItem(FAMILY_MAP_TAB, HarborGlyph.FAMILY, selected == FAMILY_MAP_TAB) { onSelect(FAMILY_MAP_TAB) })
    add(HarborNavItem("Apps", HarborGlyph.APPS, selected == "Apps") { onSelect("Apps") })
    add(HarborNavItem("About", HarborGlyph.ABOUT, selected == "About") { onSelect("About") })
}
