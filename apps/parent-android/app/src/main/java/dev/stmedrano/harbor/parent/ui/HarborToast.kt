package dev.stmedrano.harbor.parent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Toast tones by priority: a new toast only replaces one of the same or lower priority. */
enum class HarborToastTone(val glyph: HarborGlyph, val showMs: Long?) {
    SUCCESS(HarborGlyph.TOAST_SUCCESS, 4_000), INFO(HarborGlyph.TOAST_INFO, 4_000), REVIEW(HarborGlyph.TOAST_REVIEW, 6_000),
    /** Stays until dismissed. */
    URGENT(HarborGlyph.TOAST_URGENT, null),
}

class HarborToastMessage(val tone: HarborToastTone, val title: String, val detail: String? = null,
    val action: String? = null, val onAction: (() -> Unit)? = null)

/**
 * One toast at a time, floating above the nav dock (design system `Toast`). A toast is never the only record: whatever
 * it announces stays on its screen (a request under Requests, an alert in Alerts).
 */
object HarborToasts {
    private val state = MutableStateFlow<HarborToastMessage?>(null)
    val current: StateFlow<HarborToastMessage?> = state.asStateFlow()

    /** Shows [message] unless a more important toast is up. Returns whether it is now showing. */
    fun show(message: HarborToastMessage): Boolean {
        var shown = false
        state.update { now ->
            if (now == null || message.tone.ordinal >= now.tone.ordinal) { shown = true; message } else now
        }
        return shown
    }

    fun dismiss(message: HarborToastMessage) { state.compareAndSet(message, null) }
}

/** Shows the current toast and lets it leave on its own (urgent ones stay until dismissed). */
@Composable
fun HarborToastHost(modifier: Modifier = Modifier) {
    val message by HarborToasts.current.collectAsState()
    message?.let { shown -> LaunchedEffect(shown) { shown.tone.showMs?.let { delay(it); HarborToasts.dismiss(shown) } } }
    AnimatedVisibility(message != null, modifier, enter = fadeIn(tween(220)) + slideInVertically(tween(420)) { it / 4 },
        exit = fadeOut(tween(220))) {
        message?.let { HarborToast(it, onDismiss = { HarborToasts.dismiss(it) }) }
    }
}

@Composable
fun HarborToast(message: HarborToastMessage, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = harbor
    val tone = when (message.tone) {
        HarborToastTone.SUCCESS -> c.toastSuccess
        HarborToastTone.INFO -> c.toastInfo
        HarborToastTone.REVIEW -> c.toastReview
        HarborToastTone.URGENT -> c.toastUrgent
    }
    Surface(modifier.widthIn(max = 380.dp).fillMaxWidth().semantics {
        liveRegion = if (message.tone == HarborToastTone.URGENT) LiveRegionMode.Assertive else LiveRegionMode.Polite
    }, shape = RoundedCornerShape(16.dp), color = c.toastBg,
        border = BorderStroke(1.dp, if (message.tone == HarborToastTone.URGENT) c.toastUrgent else c.toastBorder),
        shadowElevation = 12.dp) {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(tone.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                HarborIcon(message.tone.glyph, tone, Modifier.size(18.dp), strokeWidth = 2.2f)
            }
            Column(Modifier.weight(1f)) {
                Text(message.title, style = HarborType.toastTitle, color = c.toastInk, maxLines = 2, overflow = TextOverflow.Ellipsis)
                message.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = c.toastSub) }
            }
            if (message.action != null && message.onAction != null) {
                TextButton({ message.onAction.invoke(); onDismiss() }, Modifier.heightIn(min = 44.dp)) {
                    Text(message.action, color = tone, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }
            IconButton(onDismiss, Modifier.size(44.dp).semantics { contentDescription = "Dismiss" }) {
                HarborIcon(HarborGlyph.CLOSE, c.toastSub, Modifier.size(20.dp))
            }
        }
    }
}
