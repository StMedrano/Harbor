package dev.stmedrano.harbor.parent.controls

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.access.AccessReason
import dev.stmedrano.harbor.parent.access.AskResult
import dev.stmedrano.harbor.parent.access.askButtonText
import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.ui.HarborTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Harbor's own block screen, in Harbor's colours. It says plainly which rule applies and lets the child ask a parent
 * for more time or access; the parent gets a notification and answers from their Controls tab. It never imitates a
 * system dialog or another app, and it is kept out of the recent-apps list.
 */
class BlockActivity : ComponentActivity() {
    private data class Blocked(val packageName: String?, val label: String, val reason: BlockReason, val daily: Boolean)
    private var blocked by mutableStateOf(Blocked(null, "This app", BlockReason.BLOCKED_APP, false))

    private fun read(intent: Intent) = Blocked(
        intent.getStringExtra(EXTRA_PACKAGE)?.takeIf(::isPackageName),
        intent.getStringExtra(EXTRA_LABEL)?.take(64) ?: "This app",
        intent.getStringExtra(EXTRA_REASON)?.let { runCatching { BlockReason.valueOf(it) }.getOrNull() } ?: BlockReason.BLOCKED_APP,
        intent.getBooleanExtra(EXTRA_DAILY, false),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        blocked = read(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = goHome() })
        setContent { HarborTheme { key(blocked) { BlockScreen(blocked) } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        blocked = read(intent)
    }

    private enum class Ask { IDLE, SENDING, SENT, LIMITED, FAILED }

    @Composable
    private fun BlockScreen(b: Blocked) {
        val graph = application as ParentApplication
        val asker = graph.childAccessAsker
        val reason = AccessReason.of(b.reason, b.daily)
        val (title, body) = blockMessage(b.reason, b.label, ControlsBridge.policy, b.daily)
        var ask by remember { mutableStateOf(if (b.packageName != null && asker?.askedRecently(b.packageName) == true) Ask.SENT else Ask.IDLE) }
        var nowAllowed by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val icon = remember(b.packageName) { b.packageName?.let { appIcon(this, it) } }
        // A parent's yes (or a resume or unblock) reaches this phone as new controls; offer to open the app when it does.
        LaunchedEffect(b) {
            while (true) {
                nowAllowed = b.packageName != null && blockLifted(b.packageName, b.reason, ControlsBridge.policy)
                delay(1_500)
            }
        }
        val brand = MaterialTheme.colorScheme.primary
        val onBrand = MaterialTheme.colorScheme.onPrimary
        Column(Modifier.fillMaxSize().background(brand).systemBarsPadding().padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HarborMark(26.dp, onBrand, brand)
                Text("Harbor Family", color = onBrand, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.BottomEnd) {
                if (icon != null) Image(icon, null, Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)))
                else HarborMark(84.dp, onBrand, brand)
                Box(Modifier.offset(8.dp, 8.dp).size(34.dp).clip(CircleShape).background(BEACON), contentAlignment = Alignment.Center) {
                    LockGlyph(brand)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(title, color = onBrand, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(body, color = onBrand.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        nowAllowed -> {
                            Text("Your parent said yes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${b.label} is open to you now.", style = MaterialTheme.typography.bodyMedium)
                            Button({ openApp(b.packageName) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open ${b.label}") }
                        }
                        b.packageName == null || asker == null -> Text("Ask a parent if you think you need this app.",
                            style = MaterialTheme.typography.bodyMedium)
                        ask == Ask.SENT -> {
                            Text("Asked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Your parent got a notification. You'll get one too when they answer.", style = MaterialTheme.typography.bodyMedium)
                        }
                        ask == Ask.LIMITED -> {
                            Text("You've asked a lot this hour", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("Try again a bit later.", style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            Text("Need it for something?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (ask == Ask.FAILED) "That didn't send. Check your connection and try again."
                                else "Your parent gets a notification and can say yes or no.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (ask == Ask.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                            Button({
                                ask = Ask.SENDING
                                scope.launch {
                                    ask = when (asker.ask(reason, b.packageName, b.label)) {
                                        AskResult.SENT -> Ask.SENT; AskResult.LIMITED -> Ask.LIMITED; AskResult.FAILED -> Ask.FAILED
                                    }
                                }
                            }, Modifier.fillMaxWidth().heightIn(min = 48.dp), enabled = ask != Ask.SENDING) {
                                if (ask == Ask.SENDING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(askButtonText(reason, b.label))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(::goHome, Modifier.fillMaxWidth().heightIn(min = 48.dp), border = BorderStroke(1.5.dp, onBrand.copy(alpha = 0.6f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = onBrand)) { Text("Go to home screen") }
            Spacer(Modifier.height(10.dp))
            Text("Calls and texts always work.", color = onBrand.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
        }
    }

    private fun openApp(packageName: String?) {
        val launch = packageName?.let { packageManager.getLaunchIntentForPackage(it) } ?: return goHome()
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_PACKAGE = "package"
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_DAILY = "daily"
        private val BEACON = Color(0xFFE9A23B)
        fun intent(context: Context, packageName: String, label: String, reason: BlockReason, daily: Boolean = false): Intent =
            Intent(context, BlockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_REASON, reason.name).putExtra(EXTRA_DAILY, daily)
                // NO_USER_ACTION: the covered app is not told the user is leaving it, so a video app does not switch to
                // picture-in-picture and keep playing behind the block screen.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_NO_USER_ACTION)

        private fun appIcon(context: Context, packageName: String): ImageBitmap? = try {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 192, 192)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        } catch (_: Exception) { null }
    }
}

/** The Harbor mark (a home outline with a Beacon light) at any size. */
@Composable
private fun HarborMark(size: Dp, container: Color, ink: Color) {
    Canvas(Modifier.size(size)) {
        val unit = this.size.width / 32f
        drawRoundRect(container, cornerRadius = CornerRadius(8f * unit))
        val house = Path().apply {
            moveTo(8f * unit, 15f * unit); lineTo(16f * unit, 8f * unit)
            lineTo(24f * unit, 15f * unit); lineTo(24f * unit, 24f * unit)
            lineTo(8f * unit, 24f * unit); close()
        }
        drawPath(house, ink, style = Stroke(2.4f * unit, join = StrokeJoin.Round))
        drawCircle(Color(0xFFE9A23B), 2.6f * unit, Offset(16f * unit, 18f * unit))
    }
}

/** A small padlock drawn in [ink], for the badge on the blocked app's icon. */
@Composable
private fun LockGlyph(ink: Color) {
    Canvas(Modifier.size(18.dp)) {
        val u = this.size.width / 24f
        drawRoundRect(ink, topLeft = Offset(5f * u, 10.5f * u), size = androidx.compose.ui.geometry.Size(14f * u, 10f * u),
            cornerRadius = CornerRadius(2.5f * u))
        val shackle = Path().apply { moveTo(8f * u, 10.5f * u); lineTo(8f * u, 7.5f * u)
            cubicTo(8f * u, 2.5f * u, 16f * u, 2.5f * u, 16f * u, 7.5f * u); lineTo(16f * u, 10.5f * u) }
        drawPath(shackle, ink, style = Stroke(2.2f * u))
    }
}
