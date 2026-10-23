package dev.stmedrano.harbor.parent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import dev.stmedrano.harbor.parent.usage.formatUsageDuration
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.legal.LegalDoc

@Composable fun ChildDashboard(state: ChildSyncState, binding: ChildBinding, onSync: () -> Unit, onRequestRoleChange: () -> Unit,
    notificationConfirmed: Boolean = false, onEnableNotifications: (() -> Unit)? = null, usage: ChildUsageUi? = null,
    location: ChildLocationUi? = null, alerts: ChildAlertsUi? = null, checkIn: ChildCheckInUi? = null, familyMap: ChildFamilyMapUi? = null,
    controls: ChildControlsUi? = null,
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val (eyebrow, title) = childHeader(current)
        HarborScreenHeader(eyebrow, title ?: rememberGreeting(GreetingAudience.CHILD))
        if (tab == null) HarborTabs(childTabs(checkIn != null, familyMap != null).map { title -> HarborTab(title, current == title) { localTab = title } })
        when (current) {
            "Today" -> {
                HarborCard {
                    Text("Connected to your family", style = MaterialTheme.typography.titleMedium)
                    when (state) {
                        is ChildSyncState.Fresh ->
                            Text("Synced ${formatTimestamp(java.time.Instant.ofEpochSecond(state.receivedAt).toString())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        is ChildSyncState.Stale -> {
                            HarborNote("Showing saved setup. Current status has not been confirmed.", HarborTone.WARN)
                            state.lastSuccessAt?.let { Text("Last confirmed sync: ${formatTimestamp(java.time.Instant.ofEpochSecond(it).toString())}", style = MaterialTheme.typography.bodySmall) }
                        }
                        is ChildSyncState.Blocked -> Unit
                    }
                }
                checkIn?.state?.pending?.let { pending -> CheckInRequestBanner(pending) { selectTab(CHECK_IN_TAB) } }
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
                if (usage != null) ChildScreenTimeCard(usage.view) { selectTab("Apps") }
                if (controls != null) ControlsPanel(controls)
                HarborOutlinedButton(onClick = onSync, modifier = Modifier.fillMaxWidth()) { Text("Sync now") }
            }
            CHECK_IN_TAB -> if (checkIn != null) CheckInScreen(checkIn) else Text("Check-ins are starting. Try again in a moment.")
            FAMILY_MAP_TAB -> if (familyMap != null) FamilyMapScreen(familyMap) else Text("The family map is starting. Try again in a moment.")
            "Apps" -> if (usage != null) UsageAppsPanel(usage.view) else Text("App information is not available yet.")
            "About" -> {
                ChildTransparency(location = location != null, usage = usage != null, alerts = alerts != null, controls = controls != null,
                    checkIn = checkIn != null)
                LegalBody(rememberLegalBlocks(LegalDoc.CHILD_ABOUT), showTitle = false)
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

/** Today's screen time on the child's phone, and the way to the Apps tab (child view design). */
@Composable
fun ChildScreenTimeCard(state: dev.stmedrano.harbor.parent.usage.UsageViewState, onApps: () -> Unit) {
    UsageNoticeList(state)
    val measured = state.status == dev.stmedrano.harbor.parent.usage.UsageViewStatus.MEASURED
    HarborCard {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Your screen time today", style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
                Text(if (measured) formatUsageDuration(state.today?.totalMs) else "—", style = HarborMetricStyle, color = harbor.ink)
            }
            HarborSecondaryButton(onApps, small = true) { Text("See your apps") }
        }
    }
}

/**
 * Design system `Transparency`: what parents can see and control, and what Harbor never does, in plain sentences. Each
 * line is shown only when that part of Harbor is running on this phone, so the list always matches the parent app.
 */
@Composable
fun ChildTransparency(location: Boolean, usage: Boolean, alerts: Boolean, controls: Boolean, checkIn: Boolean) {
    val can = buildList {
        if (location) add("Where this phone is, how accurate that is and when it was sent. Harbor keeps places for 30 days.")
        if (usage) add("How long you use this phone each day, and which apps.")
        if (controls) add("Which apps were open and when. Your parents can set daily time limits, app limits, bedtime and blocked apps, and pause the phone. When time is up, only apps set to Unlimited work. Removing an app needs their OK.")
        if (alerts) add("When a notification is flagged: the app, the time and the kind of concern.")
        if (checkIn) add("Your answer when a parent asks you to check in, with this phone's last known place.")
    }
    if (can.isNotEmpty()) HarborCard(title = "What your parents can see") {
        can.forEach { line -> TransparencyLine(line, harbor.acc) }
    }
    HarborCard(title = "What Harbor does not do") {
        if (alerts) TransparencyLine("It never sends your message text to your parents.", harbor.ink3)
        TransparencyLine("It never sees what's on your screen, only which app is open.", harbor.ink3)
        TransparencyLine("It can't use your camera or microphone.", harbor.ink3)
    }
}

@Composable
private fun TransparencyLine(text: String, dot: androidx.compose.ui.graphics.Color) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        androidx.compose.foundation.layout.Box(Modifier.padding(top = 8.dp).size(8.dp)
            .background(dot, androidx.compose.foundation.shape.CircleShape))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = harbor.ink)
    }
}

/** Each child tab's eyebrow and title; Today opens with a greeting instead (null title). */
fun childHeader(tab: String): Pair<String, String?> = when (tab) {
    CHECK_IN_TAB -> "Your parents asked" to "Share my check-in"
    FAMILY_MAP_TAB -> "Your family" to "Family map"
    "Apps" -> "Your apps" to "Your apps today"
    "About" -> "What your parents see" to "About Harbor on your phone"
    else -> "Today" to null
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
