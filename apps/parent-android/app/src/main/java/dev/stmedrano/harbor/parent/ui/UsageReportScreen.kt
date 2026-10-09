package dev.stmedrano.harbor.parent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.usage.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val stampFormat = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private fun stamp(iso: String?): String = iso?.let { runCatching { stampFormat.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull() } ?: "not yet"
private fun dayLabel(localDate: String): String = runCatching {
    java.time.LocalDate.parse(localDate).format(DateTimeFormatter.ofPattern("MMM d", java.util.Locale.getDefault()))
}.getOrDefault(localDate)

@Composable
fun UsageNoticeList(state: UsageViewState) {
    usageNotices(state).forEach { notice ->
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
            Text(notice, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun UsageTimes(state: UsageViewState) {
    Column {
        Text("Last confirmed upload: ${stamp(state.receivedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.observedAt?.let { Text("Measured through: ${stamp(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        state.zoneId?.let { Text("Time zone: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun DayBars(days: List<UsageDayRow>) {
    val known = days.mapNotNull { it.totalMs }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    Row(Modifier.fillMaxWidth().height(136.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        days.forEach { day ->
            val description = "${dayLabel(day.localDate)}: ${if (day.totalMs == null) "unknown" else formatUsageDuration(day.totalMs)}" +
                if (day.quality == UsageQuality.PARTIAL) ", partial day" else ""
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = description },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                if (day.totalMs == null) {
                    Box(Modifier.fillMaxWidth().height(22.dp).border(BorderStroke(1.5.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Text("?", style = MaterialTheme.typography.labelSmall)
                    }
                } else {
                    Box(Modifier.fillMaxWidth().height((day.totalMs.toFloat() / known * 104f).coerceAtLeast(2f).dp)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp)))
                }
                Text(dayLabel(day.localDate).takeLast(2).trim(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
fun UsageTodayPanel(state: UsageViewState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UsageNoticeList(state)
        val today = state.today
        if (state.status == UsageViewStatus.MEASURED && today != null) {
            HarborPanel("${dayLabel(today.localDate)} · measured total") {
                Text(formatUsageDuration(today.totalMs), style = MaterialTheme.typography.headlineMedium)
                DayBars(state.days)
                Text("Apps used side by side can add up to more than the total, which counts overlapping time once.", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.status == UsageViewStatus.MEASURED || state.receivedAt != null) UsageTimes(state)
        Text("Reported by the child's phone and not independently verified. It will not match Digital Wellbeing exactly.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun UsageAppsPanel(state: UsageViewState) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        UsageNoticeList(state)
        if (state.status != UsageViewStatus.MEASURED) return@Column
        val unmeasured = if (state.today?.quality == UsageQuality.UNAVAILABLE) "Not measured" else "None recorded"
        if (state.apps.isEmpty()) Text("No launchable apps were reported.")
        else HarborPanel("Apps today") {
            state.apps.forEachIndexed { index, app ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(app.label ?: app.packageName)
                        Text(if (app.label == null) "${app.packageName} · name unavailable" else app.packageName, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (app.foregroundMs == null) unmeasured else formatUsageDuration(app.foregroundMs))
                }
            }
        }
        Text("Apps are the launchable apps visible to this Android profile. This is a measurement only; Harbor does not limit or block apps from this view.",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Read-only report with Today and Apps views; used for the parent's device detail. */
@Composable
fun UsageReportScreen(state: UsageViewState, onRefresh: (() -> Unit)? = null) {
    var tab by remember { mutableStateOf("Today") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Screen time", style = MaterialTheme.typography.titleLarge)
        Row { listOf("Today", "Apps").forEach { title -> TextButton(onClick = { tab = title }) { Text(title) } } }
        if (tab == "Today") UsageTodayPanel(state) else UsageAppsPanel(state)
        if (onRefresh != null) OutlinedButton(onRefresh, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Refresh") }
    }
}
