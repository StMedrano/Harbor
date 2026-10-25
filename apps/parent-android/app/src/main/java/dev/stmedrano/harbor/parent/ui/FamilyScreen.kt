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
import dev.stmedrano.harbor.parent.data.FamilyParentV1
import dev.stmedrano.harbor.parent.family.*
import dev.stmedrano.harbor.parent.legal.LegalDoc
import java.time.Instant

/** Leaving the family on screen, or deleting it when nobody else is in it and it is empty; nothing that would be refused is offered. */
@Composable
private fun LeaveFamilyCard(snapshot: dev.stmedrano.harbor.parent.data.FamilySnapshot, online: Boolean, onLeave: () -> Unit) {
    val others = snapshot.parents.count { it.userId != snapshot.membership.userId }
    val name = snapshot.family.name
    HarborCard {
        when {
            others > 0 -> HarborRow("Leave $name", subtitle = "The other parents keep everything. A new invite code brings you back.",
                onClick = if (online) onLeave else null, trailing = { HarborChevron() })
            snapshot.children.isEmpty() -> HarborRow("Delete $name", subtitle = "You're its only parent and it has no children.",
                onClick = if (online) onLeave else null, trailing = { HarborChevron() })
            else -> HarborRow("You're its only parent",
                subtitle = "A family with children always keeps a parent. Invite another parent before you leave.")
        }
    }
}

@Composable
fun FamilyScreen(state: FamilyState, families: List<FamilyV1>, selectedChildId: String?,
    onRefresh: () -> Unit, onSelectFamily: (String) -> Unit, onSelectChild: (String) -> Unit,
    onCreateFamily: () -> Unit, onAddChild: () -> Unit, onPair: () -> Unit, onDevice: (DevicePublicV1) -> Unit,
    onInviteParent: (() -> Unit)? = null, onJoinFamily: (() -> Unit)? = null, onMyName: (() -> Unit)? = null,
    onLeaveFamily: (() -> Unit)? = null,
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
                HarborEmptyState("Get started", art = HarborArt.ANCHOR,
                    body = "Start your own family, or join one another parent already set up.",
                    action = "Start a family", onAction = onCreateFamily, actionEnabled = online)
                if (onJoinFamily != null) HarborSecondaryButton(onJoinFamily, enabled = online, modifier = Modifier.fillMaxWidth()) {
                    Text("I have an invite code")
                }
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
            // Every parent of a family sees the same children and phones and can change the same controls.
            HarborCard(title = "Parents") {
                val parents = snapshot.parents.ifEmpty {
                    listOf(FamilyParentV1(snapshot.membership.userId, snapshot.membership.role, snapshot.profile?.displayName))
                }
                parents.forEachIndexed { index, parent ->
                    val you = parent.userId == snapshot.membership.userId
                    val named = parent.displayName?.takeIf { it.isNotBlank() }
                    val name = named ?: if (you) "You" else "A parent"
                    val role = if (parent.role == "owner") "Started this family" else "Parent"
                    HarborRow(if (you && named != null) "$name (you)" else name, divider = index > 0,
                        // Only your own name is yours to change; the others set theirs on their own phone.
                        subtitle = if (you) "$role · tap to change your name" else role,
                        onClick = if (you) onMyName else null,
                        leading = { HarborAvatar(name, 36.dp, HarborParentColor) },
                        trailing = if (you && onMyName != null) ({ HarborChevron() }) else null)
                }
                Text("Every parent sees the same children and phones, and can change the same rules. When two parents change a rule at the same time, both changes are kept.",
                    style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
                if (onInviteParent != null) HarborSecondaryButton(onInviteParent, enabled = online, modifier = Modifier.fillMaxWidth()) {
                    Text("Invite a parent")
                }
                // A parent who already has an account, and already has a family of their own, still joins with a code.
                if (onJoinFamily != null) HarborOutlinedButton(onJoinFamily, enabled = online, modifier = Modifier.fillMaxWidth()) {
                    Text("Join another family with a code")
                }
            }
            TextButton(onCreateFamily, enabled = online) { Text("Start another family") }
            if (onLeaveFamily != null) LeaveFamilyCard(snapshot, online, onLeaveFamily)
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
