package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.activity.*
import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun rememberParentActivityView(model: ParentActivityModel?, identity: ParentIdentity, deviceId: String, day: LocalDate, resumeTick: Int = 0): ActivityViewState? {
    if (model == null) return null
    LaunchedEffect(model, identity, deviceId, day, resumeTick) {
        while (true) { model.load(identity, deviceId, day); delay(30_000) }
    }
    val scoped = model.state.collectAsState().value
    return scoped?.takeIf { it.scope == ParentActivityModel.Scope(identity, deviceId, day) }?.view
}

private val clock = DateTimeFormatter.ofPattern("h:mm a")
private val dayLabel = DateTimeFormatter.ofPattern("EEE, MMM d")

/**
 * The day's activity: which apps were used and when, and what the app controls did. Names and times only; Harbor never
 * sees what was on the screen.
 */
@Composable
fun ParentActivityScreen(view: ActivityViewState, today: LocalDate, onDay: (LocalDate) -> Unit, onRefresh: (() -> Unit)?) {
    HarborPanel("Activity") {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton({ onDay(view.day.minusDays(1)) }, enabled = view.day > today.minusDays(29)) { Text("‹ Earlier") }
            Text(if (view.day == today) "Today" else view.day.format(dayLabel), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f))
            TextButton({ onDay(view.day.plusDays(1)) }, enabled = view.day < today) { Text("Later ›") }
        }
        when (view.status) {
            ActivityViewStatus.ACCESS_LOST -> HarborNote("You no longer have access to this phone's activity.", HarborTone.DANGER, emphasis = true)
            ActivityViewStatus.LOAD_FAILED -> HarborNote("Harbor couldn't load activity. Check your connection.", HarborTone.WARN)
            ActivityViewStatus.NONE -> Text("The phone hasn't reported any activity yet.")
            ActivityViewStatus.EXPIRED -> Text("Activity older than 30 days is deleted.")
            ActivityViewStatus.AVAILABLE -> {
                if (view.offline) HarborNote("Showing what was last loaded. Couldn't refresh.", HarborTone.WARN)
                if (view.controlsOn == false) HarborNote("App controls are off on the phone.", HarborTone.DANGER, emphasis = true)
                if (view.totals.isNotEmpty()) {
                    Text("Time by app", style = MaterialTheme.typography.titleSmall)
                    view.totals.take(8).forEach { t -> Text("${t.label}: ${formatMinutes(t.ms)}") }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                if (view.events.isEmpty()) Text("No activity this day.")
                val zone = ZoneId.systemDefault()
                view.events.asReversed().forEach { e ->
                    Row(Modifier.fillMaxWidth()) {
                        Text(Instant.ofEpochMilli(alertInstantMs(e.at)).atZone(zone).format(clock), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.width(72.dp))
                        Text(activityLine(e), modifier = Modifier.weight(1f))
                    }
                }
                if (view.truncated) Text("Showing the first 500 entries for this day.", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("Harbor sees which apps were open and when, never what was on the screen.", style = MaterialTheme.typography.bodySmall)
        if (onRefresh != null && view.status != ActivityViewStatus.ACCESS_LOST) HarborSecondaryButton(onRefresh, Modifier.fillMaxWidth()) { Text("Refresh activity") }
    }
}

fun formatMinutes(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "${maxOf(1L, minutes)} min"
}
