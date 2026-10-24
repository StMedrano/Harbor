package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import dev.stmedrano.harbor.parent.family.ParentInvite

/** Where the other adult enters the code, shown in both invite sheets so the steps are the same on either phone. */
const val INVITE_WEB = "harbor-git-feat-activity-app-control-stalinvmedrano-1274.vercel.app"

/**
 * The code another adult enters to join this family as a parent, in the design system's `PairingCode` shape: numbered
 * steps, the eight characters grouped 4 + 4, and how long it works. A new code replaces the parent's earlier one.
 */
@Composable
fun InviteParentSheet(invite: ParentInvite?, busy: Boolean, error: String?, onRetry: () -> Unit, onClose: () -> Unit) {
    val c = harbor
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, containerColor = c.s2,
        title = { Text("Invite a parent", style = MaterialTheme.typography.titleLarge) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("The other parent uses their own account and sees everything you see: the same children, phones, alerts and controls.",
                    style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InviteStep(1, "They install Harbor Family, or open $INVITE_WEB.")
                    InviteStep(2, "They create an account with their own email.")
                    InviteStep(3, "They choose \"I have an invite code\" and enter this code.")
                }
                if (busy && invite == null) CircularProgressIndicator(color = c.acc)
                if (invite != null) {
                    Surface(shape = MaterialTheme.shapes.medium, color = c.s, border = BorderStroke(1.dp, c.line), modifier = Modifier.fillMaxWidth()) {
                        Text(inviteCodeText(invite.code), Modifier.padding(horizontal = 8.dp, vertical = 14.dp), style = HarborCodeStyle,
                            color = c.ink, textAlign = TextAlign.Center)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(c.acc))
                        Text("Works once, until ${formatTimestamp(invite.expiresAt)}. A new code replaces this one.",
                            style = MaterialTheme.typography.bodySmall, color = c.ink2)
                    }
                }
                if (error != null) HarborNote(error, HarborTone.DANGER, emphasis = true)
                if (invite == null && !busy) HarborButton(onRetry, Modifier.fillMaxWidth()) { Text("Get a code") }
            }
        }, confirmButton = { TextButton(onClose, enabled = !busy) { Text("Done") } })
}

/** The other adult's side: they type the code the first parent read out. */
@Composable
fun JoinFamilySheet(code: String, busy: Boolean, error: String?, onCode: (String) -> Unit, onJoin: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!busy) onClose() }, containerColor = harbor.s2,
        title = { Text("Join a family", style = MaterialTheme.typography.titleLarge) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HarborField(code, onCode, "Invite code", enabled = !busy,
                    hint = "Eight characters from the other parent's Harbor app: Family > Invite a parent. A code works once, for one day.",
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters))
                if (error != null) HarborNote(error, HarborTone.DANGER, emphasis = true)
                HarborButton(onJoin, Modifier.fillMaxWidth(), enabled = !busy && inviteCodeReady(code), loading = busy) { Text("Join family") }
            }
        }, confirmButton = { TextButton(onClose, enabled = !busy) { Text("Close") } })
}

/** Eight characters read as 4 + 4, the way the code is shown and spoken. */
fun inviteCodeText(code: String): String {
    val plain = code.filter { it.isLetterOrDigit() }
    return if (plain.length == 8) "${plain.take(4)}-${plain.drop(4)}" else code
}
/** Enough characters typed to be worth sending; the server decides whether the code is real. */
fun inviteCodeReady(code: String): Boolean = code.count { it.isLetterOrDigit() } == 8

@Composable
private fun InviteStep(number: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(harbor.accs), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelMedium, color = harbor.acc)
        }
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = harbor.ink2)
    }
}
