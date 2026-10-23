package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.legal.LegalDoc
import java.time.Instant

@Composable
fun FamilyScreen(state: FamilyState, families: List<FamilyV1>, selectedChildId: String?,
    onRefresh: () -> Unit, onSelectFamily: (String) -> Unit, onSelectChild: (String) -> Unit,
    onCreateFamily: () -> Unit, onAddChild: () -> Unit, onPair: () -> Unit, onDevice: (DevicePublicV1) -> Unit,
    onSettings: (() -> Unit)? = null, onSecurity: (() -> Unit)? = null, onOpenDoc: ((LegalDoc) -> Unit)? = null,
    sharing: ParentSharingUi? = null) {
    val snapshot = state.snapshot
    val online = !state.cached && state.failure == null && !state.loading
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ParentTabHeader(ParentTab.FAMILY, subtitle = snapshot?.family?.name ?: "Your family")
        if (state.loading) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp); Text("Refreshing family…", color = harbor.ink2)
        }
        if (state.cached && snapshot != null) HarborNote("Cached view · last refreshed ${Instant.ofEpochMilli(snapshot.fetchedAt)}. Connect to make changes.", HarborTone.WARN)
        if (state.failure != null) HarborNote(if (state.failure == FamilyFailure.ACCESS_DENIED) "Family access was removed." else "Refresh failed. Check your connection and retry.", HarborTone.DANGER, emphasis = true)
        if (families.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                families.forEach { family -> HarborChip(family.name, snapshot?.family?.id == family.id, { onSelectFamily(family.id) }) }
            }
        }
        if (snapshot == null) {
            HarborCard {
                HarborEmptyState("Get started", art = HarborArt.ANCHOR, body = "Create or select a family to get started.",
                    action = "Create family", onAction = onCreateFamily, actionEnabled = online)
            }
        } else {
            if (snapshot.children.isEmpty()) HarborCard {
                HarborEmptyState("No children yet", art = HarborArt.ANCHOR, body = "Add a child, then pair their phone.")
            }
            snapshot.children.forEachIndexed { index, child ->
                val selected = selectedChildId == child.id
                val phones = snapshot.devices.filter { it.childId == child.id && it.status != "revoked" }
                // Design system `MemberCard`: the selected child is ringed in brand; their phones open the device page.
                HarborCard(border = if (selected) harbor.brand else harbor.line) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(MaterialTheme.shapes.medium)
                        .selectable(selected = selected, role = Role.RadioButton) { onSelectChild(child.id) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HarborAvatar(child.displayName, 42.dp, HarborKid.color(index))
                        Column(Modifier.weight(1f)) {
                            Text(child.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(when (phones.size) { 0 -> "No phone connected."; 1 -> "1 phone"; else -> "${phones.size} phones" },
                                style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
                        }
                        if (selected) HarborTag("Selected", HarborTagTone.LOW)
                    }
                    phones.forEach { device ->
                        HarborRow("${device.displayName} · ${device.status} · ${device.supervisionMode}", divider = true,
                            onClick = { onDevice(device) }, leading = { HarborIcon(HarborGlyph.PHONE, harbor.ink2) }, trailing = { HarborChevron() })
                    }
                }
            }
            HarborButton(onAddChild, enabled = online, modifier = Modifier.fillMaxWidth()) { Text("Add child") }
            HarborSecondaryButton(onPair, enabled = online && selectedChildId != null, modifier = Modifier.fillMaxWidth()) { Text("Pair device") }
            TextButton(onCreateFamily, enabled = online) { Text("Create family") }
        }
        HarborOutlinedButton(onRefresh, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) { Text("Refresh family") }
        if (snapshot != null && sharing != null) ParentSharingPanel(sharing)
        if (onSettings != null || onSecurity != null || onOpenDoc != null) HarborCard {
            if (onSettings != null) HarborRow("Settings", onClick = onSettings, leading = { HarborIcon(HarborGlyph.SETTINGS, harbor.ink2) }, trailing = { HarborChevron() })
            if (onSecurity != null) HarborRow("Security", divider = onSettings != null, onClick = onSecurity,
                leading = { HarborIcon(HarborGlyph.SECURITY, harbor.ink2) }, trailing = { HarborChevron() })
            if (onOpenDoc != null) {
                val lead = onSettings != null || onSecurity != null
                HarborRow("About Harbor", divider = lead, onClick = { onOpenDoc(LegalDoc.PARENT_ABOUT) },
                    leading = { HarborIcon(HarborGlyph.LIGHTHOUSE, harbor.ink2) }, trailing = { HarborChevron() })
                HarborRow("Privacy policy", divider = true, onClick = { onOpenDoc(LegalDoc.PRIVACY) },
                    leading = { HarborIcon(HarborGlyph.LOCK, harbor.ink2) }, trailing = { HarborChevron() })
                HarborRow("Terms of service", divider = true, onClick = { onOpenDoc(LegalDoc.TERMS) },
                    leading = { HarborIcon(HarborGlyph.ANCHOR, harbor.ink2) }, trailing = { HarborChevron() })
            }
        }
    }
}
