package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.familymap.FamilyMapPerson
import dev.stmedrano.harbor.parent.familymap.FamilyMapUiState
import dev.stmedrano.harbor.parent.familymap.PersonKind
import dev.stmedrano.harbor.parent.location.locationAgeLabel
import dev.stmedrano.harbor.parent.location.locationInstantMs
import dev.stmedrano.harbor.parent.profile.ProfileLease
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the child's Family page needs. It is read-only: nothing here changes what anyone shares. */
data class ChildFamilyMapUi(val state: FamilyMapUiState, val onRefresh: () -> Unit)

/** Re-reads the map when the app comes back to the front and then every minute while this page is open. */
@Composable
fun rememberChildFamilyMapUi(graph: ParentApplication, lease: ProfileLease, resumeTick: Int): ChildFamilyMapUi? {
    var model by remember(lease) { mutableStateOf(graph.childFamilyMapModel) }
    LaunchedEffect(lease, resumeTick) {
        while (true) {
            val current = graph.childFamilyMapModel
            model = current
            current?.refresh(lease)
            delay(if (current == null) 1_000L else 60_000L)
        }
    }
    val scope = rememberCoroutineScope()
    val active = model ?: return null
    return ChildFamilyMapUi(active.state.collectAsState().value) { scope.launch { active.refresh(lease) } }
}

/** A place older than this is drawn grey, like the parent's map. */
const val FAMILY_MAP_STALE_MS = 30L * 60_000L

internal fun mapPeople(people: List<FamilyMapPerson>, nowMs: Long): List<MapPerson> {
    val kids = people.filter { it.kind == PersonKind.CHILD }
    return people.mapNotNull { p ->
        val fix = p.fix ?: return@mapNotNull null
        val seen = locationInstantMs(fix.at)
        val color = if (p.kind == PersonKind.PARENT) HarborParentColor else HarborKid.color(kids.indexOf(p))
        MapPerson(p.id, p.name, fix.latitude, fix.longitude, color, nowMs - seen > FAMILY_MAP_STALE_MS, p.self,
            "${if (p.self) "You" else p.name}, ${locationAgeLabel(nowMs - seen)}")
    }
}

@Composable
fun FamilyMapScreen(ui: ChildFamilyMapUi) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val state = ui.state
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            !state.loaded && !state.offline -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            !state.loaded -> HarborNote("Could not load the family map. Check your connection.", HarborTone.WARN)
            !state.enabled -> HarborPanel("Family map is off") { Text("A parent turned the family map off.") }
            else -> {
                if (state.offline) HarborNote("Showing the last map that loaded. It could not be refreshed just now.", HarborTone.WARN)
                val shown = mapPeople(state.people, now)
                if (shown.isEmpty()) Text("No one has shared a place yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else PeopleMap(shown)
                HarborCard {
                    state.people.forEachIndexed { index, person ->
                        val fix = person.fix
                        HarborRow(if (person.self) "${person.name} (you)" else person.name, divider = index > 0,
                            subtitle = if (fix == null) "No place yet" else "Seen ${locationAgeLabel(now - locationInstantMs(fix.at))}",
                            leading = { HarborAvatar(person.name, 36.dp, if (person.kind == PersonKind.PARENT) HarborParentColor
                                else HarborKid.color(state.people.filter { it.kind == PersonKind.CHILD }.indexOf(person))) },
                            trailing = { if (person.kind == PersonKind.PARENT) HarborTag("Parent") })
                    }
                }
                HarborOutlinedButton(ui.onRefresh, Modifier.fillMaxWidth()) { Text("Refresh") }
            }
        }
    }
}
