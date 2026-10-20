package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.familymap.FamilyMapPerson
import dev.stmedrano.harbor.parent.familymap.PersonKind
import dev.stmedrano.harbor.parent.location.locationAgeLabel
import dev.stmedrano.harbor.parent.location.locationInstantMs
import kotlinx.coroutines.delay

/** A time-of-day greeting; the small hours get a plain "Hello" rather than a cheerful morning. */
internal fun timeGreeting(hour: Int): String = when (hour) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello"
}

/** The child this phone belongs to, as the family map names them. Null until the map has loaded, or if it is switched off. */
internal fun childSelf(people: List<FamilyMapPerson>): FamilyMapPerson? = people.firstOrNull { it.self && it.kind == PersonKind.CHILD }

/** The child's position among the family's children, which picks their colour so it matches the parent's app and the map. */
internal fun childColourIndex(people: List<FamilyMapPerson>, self: FamilyMapPerson): Int =
    people.filter { it.kind == PersonKind.CHILD }.indexOf(self)

/** The top of the child's Today page: an initial, a greeting, and one plain line about whether the phone is connected. */
@Composable
fun ChildGreeting(state: ChildSyncState, familyMap: ChildFamilyMapUi?) {
    val people = familyMap?.state?.people.orEmpty()
    val me = childSelf(people)
    val hour = remember { java.time.LocalTime.now().hour }
    val greeting = timeGreeting(hour)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        HarborAvatar(me?.name ?: "H", 52.dp, if (me != null) HarborKid.color(childColourIndex(people, me)) else MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(if (me != null) "$greeting, ${me.name}" else greeting, style = MaterialTheme.typography.headlineSmall)
            Text(
                when (state) {
                    is ChildSyncState.Fresh -> "Connected to your family. Synced ${formatTimestamp(java.time.Instant.ofEpochSecond(state.receivedAt).toString())}"
                    is ChildSyncState.Stale -> "Connected to your family"
                    is ChildSyncState.Blocked -> "Waiting for a parent"
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (state) {
            is ChildSyncState.Fresh -> HarborTag("Up to date", HarborTagTone.LOW)
            is ChildSyncState.Stale -> HarborTag("Saved setup", HarborTagTone.MEDIUM)
            is ChildSyncState.Blocked -> Unit
        }
    }
}

/** Where the family map last saw this phone. Hidden until the map has loaded and while a parent has it switched off. */
@Composable
fun ChildPlaceRow(familyMap: ChildFamilyMapUi?, onOpen: () -> Unit) {
    val map = familyMap?.state ?: return
    if (!map.loaded || !map.enabled) return
    val me = childSelf(map.people) ?: return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val fix = me.fix
    HarborPanel("Your place") {
        HarborRow(
            if (fix == null) "No place yet" else "Your parents can see where your phone is",
            subtitle = if (fix == null) "It shows here once your phone has shared one." else "Seen ${locationAgeLabel(now - locationInstantMs(fix.at))}",
            onClick = onOpen,
            trailing = { HarborTag("Family map") })
    }
}
