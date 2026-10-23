package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import dev.stmedrano.harbor.parent.usage.formatUsageDuration
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
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Detailed activity", Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        DaySwitcher(view.day, today, onDay)
        when (view.status) {
            ActivityViewStatus.ACCESS_LOST -> HarborNote("You no longer have access to this phone's activity.", HarborTone.DANGER, emphasis = true)
            ActivityViewStatus.LOAD_FAILED -> HarborNote("Harbor couldn't load activity. Check your connection.", HarborTone.WARN)
            ActivityViewStatus.NONE -> HarborCard { HarborEmptyState("The phone hasn't reported any activity yet.", art = HarborArt.BUOY) }
            ActivityViewStatus.EXPIRED -> HarborCard { HarborEmptyState("Activity older than 30 days is deleted.", art = HarborArt.CALM) }
            ActivityViewStatus.AVAILABLE -> {
                if (view.offline) HarborNote("Showing what was last loaded. Couldn't refresh.", HarborTone.WARN)
                if (view.controlsOn == false) HarborNote("App controls are off on the phone.", HarborTone.DANGER, emphasis = true)
                val (figure, figureLabel) = activityDayFigure(view)
                HarborStatRow(figure to figureLabel, view.events.count { it.kind == ActivityKind.BLOCKED_OPEN }.toString() to "Blocked opens")
                if (view.totals.isNotEmpty()) HarborPanel("Time by app") {
                    val longest = view.totals.maxOf { it.ms }.coerceAtLeast(1L)
                    view.totals.take(8).forEachIndexed { index, t ->
                        if (index > 0) HorizontalDivider(color = harbor.line)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HarborAvatar(t.label, 36.dp, HarborKid.color(t.packageName.hashCode()))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row {
                                    Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                    Text(formatMinutes(t.ms), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = harbor.ink2)
                                }
                                HarborMeter(t.ms.toFloat() / longest, Modifier.height(6.dp))
                            }
                        }
                    }
                }
                HarborPanel("Timeline") {
                    val items = activityTimeline(view.events, ZoneId.systemDefault())
                    var expanded by rememberSaveable(view.day) { mutableStateOf(false) }
                    if (items.isEmpty()) HarborEmptyState("No activity this day.", art = HarborArt.CALM)
                    else HarborTimeline(timelinePreview(items, expanded))
                    if (items.size > TIMELINE_PREVIEW) HarborSecondaryButton({ expanded = !expanded }, Modifier.fillMaxWidth(), small = true) {
                        Text(if (expanded) "Show less" else "Show more (${items.size - TIMELINE_PREVIEW} earlier)")
                    }
                    if (view.truncated && expanded) Text("Showing the first 500 entries for this day.", style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
                }
            }
        }
        Text("Harbor sees which apps were open and when, never what was on the screen.", style = MaterialTheme.typography.bodySmall, color = harbor.ink2)
        if (onRefresh != null && view.status != ActivityViewStatus.ACCESS_LOST) HarborOutlinedButton(onRefresh, Modifier.fillMaxWidth()) { Text("Refresh activity") }
    }
}

/** The day shown, with chevrons to step back up to 30 days and forward to today. */
@Composable
private fun DaySwitcher(day: LocalDate, today: LocalDate, onDay: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(harbor.s2).border(1.dp, harbor.line, MaterialTheme.shapes.medium),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onDay(day.minusDays(1)) }, Modifier.semantics { contentDescription = "Earlier day" }, enabled = day > today.minusDays(29)) {
            HarborIcon(HarborGlyph.CHEVRON, harbor.acc, Modifier.size(20.dp).graphicsLayer { scaleX = -1f })
        }
        Text(if (day == today) "Today" else day.format(dayLabel), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center)
        IconButton({ onDay(day.plusDays(1)) }, Modifier.semantics { contentDescription = "Later day" }, enabled = day < today) {
            HarborIcon(HarborGlyph.CHEVRON, if (day < today) harbor.acc else harbor.line, Modifier.size(20.dp))
        }
    }
}

/**
 * The day's headline figure: the phone's own screen-time count when it reported one for this day, otherwise the time in
 * the timeline's app sessions (which leaves out sessions under a minute). A dash until there is either.
 */
fun activityDayFigure(view: ActivityViewState): Pair<String?, String> {
    val phone = view.screenTime?.takeIf { it.date == view.day.toString() }
    if (phone != null) return formatUsageDuration(phone.totalMs) to "Screen time"
    val sessions = view.totals.sumOf { it.ms }
    return (if (view.totals.isEmpty()) null else formatUsageDuration(sessions)) to "Time in apps"
}

/** The day newest first: app time in sea glass, and the moments a rule stepped in (blocks, limits, pause, bedtime) in amber. */
/** How many of the newest timeline entries show before the parent taps Show more. */
const val TIMELINE_PREVIEW = 5

/** The day's newest entries in short; the whole day once the parent asks for it. */
fun timelinePreview(items: List<HarborTimelineItem>, expanded: Boolean): List<HarborTimelineItem> =
    if (expanded) items else items.take(TIMELINE_PREVIEW)

fun activityTimeline(events: List<ActivityEvent>, zone: ZoneId): List<HarborTimelineItem> = events.asReversed().map { e ->
    HarborTimelineItem(Instant.ofEpochMilli(alertInstantMs(e.at)).atZone(zone).format(clock), activityLine(e), review = e.kind != ActivityKind.APP_SESSION)
}

fun formatMinutes(ms: Long): String {
    val minutes = ms / 60_000L
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "${maxOf(1L, minutes)} min"
}
