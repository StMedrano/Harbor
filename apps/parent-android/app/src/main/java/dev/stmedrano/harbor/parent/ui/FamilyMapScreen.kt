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
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.familymap.FamilyMapUiState
import dev.stmedrano.harbor.parent.familymap.ParentFamilyMapModel
import dev.stmedrano.harbor.parent.familymap.ParentFamilyMapState
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

/**
 * The family on one map for a parent: every child in the family, and every parent sharing right now. Children never
 * choose — their phones report — while a parent is here only when they share, and then the whole family sees them.
 * Nothing is drawn until the first read answers, so an empty map never reads as "nobody is anywhere".
 */
@Composable
fun ParentFamilyMapSection(state: ParentFamilyMapState, onRefresh: (() -> Unit)?) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    if (!state.peopleLoaded && !state.peopleOffline) return
    val kids = state.people.filter { it.kind == PersonKind.CHILD }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
        if (state.peopleOffline) HarborNote("Showing the last family map that loaded. It could not be refreshed just now.", HarborTone.WARN)
        val shown = mapPeople(state.people, now)
        if (shown.isNotEmpty()) PeopleMap(shown)
        HarborCard {
            if (state.people.isEmpty()) {
                Text("Nobody to show yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            state.people.forEachIndexed { index, person ->
                val fix = person.fix
                HarborRow(
                    if (person.self) "${person.name} (you)" else person.name,
                    divider = index > 0,
                    subtitle = when {
                        fix != null -> "Seen ${locationAgeLabel(now - locationInstantMs(fix.at))}"
                        person.kind == PersonKind.PARENT -> "Sharing, no place yet"
                        else -> "No place yet"
                    },
                    leading = {
                        HarborAvatar(person.name, 36.dp,
                            if (person.kind == PersonKind.PARENT) HarborParentColor else HarborKid.color(kids.indexOf(person)))
                    },
                    trailing = { if (person.kind == PersonKind.PARENT) HarborTag("Parent") },
                )
            }
        }
        if (onRefresh != null) HarborOutlinedButton(onRefresh, Modifier.fillMaxWidth()) { Text("Refresh the family map") }
    }
}

/** Re-reads the family map while the Map tab is open, and again when the app comes back to the front. */
@Composable
fun rememberParentFamilyPeople(
    model: ParentFamilyMapModel?,
    identity: ParentIdentity,
    familyId: String?,
    active: Boolean,
    resumeTick: Int,
): ParentFamilyMapState? {
    val state = model?.state?.collectAsState()?.value
    LaunchedEffect(model, identity, familyId, active, resumeTick) {
        if (model == null || familyId == null || !active) return@LaunchedEffect
        while (true) {
            model.refreshPeople(identity, familyId)
            delay(60_000)
        }
    }
    return state
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
