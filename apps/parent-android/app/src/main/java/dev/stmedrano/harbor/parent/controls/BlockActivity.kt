package dev.stmedrano.harbor.parent.controls

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.access.AccessReason
import dev.stmedrano.harbor.parent.access.AskResult
import dev.stmedrano.harbor.parent.access.askButtonText
import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.ui.HarborBrand
import dev.stmedrano.harbor.parent.ui.HarborButton
import dev.stmedrano.harbor.parent.ui.HarborCard
import dev.stmedrano.harbor.parent.ui.HarborGlyph
import dev.stmedrano.harbor.parent.ui.HarborIcon
import dev.stmedrano.harbor.parent.ui.HarborLogo
import dev.stmedrano.harbor.parent.ui.HarborLogoVariant
import dev.stmedrano.harbor.parent.ui.HarborOutlinedButton
import dev.stmedrano.harbor.parent.ui.HarborTheme
import dev.stmedrano.harbor.parent.ui.harbor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Harbor's own block screen, in Harbor's colours. It says plainly which rule applies and lets the child ask a parent
 * for more time or access; the parent gets a notification and answers from their Controls tab. It never imitates a
 * system dialog or another app, and it is kept out of the recent-apps list.
 */
class BlockActivity : ComponentActivity() {
    /** [removal]: covering Android's "uninstall this app?" screen ([UninstallGuard]); [packageName] is then the installer. */
    private data class Blocked(val packageName: String?, val label: String, val reason: BlockReason, val daily: Boolean, val removal: Boolean = false)
    private var blocked by mutableStateOf(Blocked(null, "This app", BlockReason.BLOCKED_APP, false))

    private fun read(intent: Intent) = Blocked(
        intent.getStringExtra(EXTRA_PACKAGE)?.takeIf(::isPackageName),
        intent.getStringExtra(EXTRA_LABEL)?.take(64) ?: "This app",
        intent.getStringExtra(EXTRA_REASON)?.let { runCatching { BlockReason.valueOf(it) }.getOrNull() } ?: BlockReason.BLOCKED_APP,
        intent.getBooleanExtra(EXTRA_DAILY, false),
        intent.getBooleanExtra(EXTRA_REMOVAL, false),
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
        val (title, body) = if (b.removal) UninstallGuard.message else blockMessage(b.reason, b.label, ControlsBridge.policy, b.daily)
        var ask by remember { mutableStateOf(if (b.packageName != null && asker?.askedRecently(b.packageName) == true) Ask.SENT else Ask.IDLE) }
        var lift by remember { mutableStateOf(BlockLift.NONE) }
        val scope = rememberCoroutineScope()
        // Removing an app shows Harbor's mark: the installer's icon would not say which app it is.
        val icon = remember(b.packageName) { b.packageName?.takeUnless { b.removal }?.let { appIcon(this, it) } }
        // A parent's yes, or a rule change (resume, unblock, limit removed), reaches this phone as new controls.
        LaunchedEffect(b) {
            val uncounted = ExemptApps.uncounted(this@BlockActivity)
            while (true) {
                val policy = ControlsBridge.policy
                val pkg = b.packageName
                if (pkg != null) {
                    val now = java.time.ZonedDateTime.now()
                    val usage = if (b.reason == BlockReason.LIMIT_REACHED) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        UsageToday.read(this@BlockActivity, now.toInstant().toEpochMilli(), now.toLocalDate().atStartOfDay(now.zone).toInstant().toEpochMilli())
                    } else null
                    val granted = policy != null && PolicyEngine.grantRemainingMs(pkg, now, policy) != null
                    // A yes for this app answers the request; once it is used up the child can ask again.
                    if (granted) asker?.forget(pkg)
                    if (ask == Ask.SENT && asker?.askedRecently(pkg) == false) ask = Ask.IDLE
                    // Removing apps opens only with a parent's yes; no rule change lifts it.
                    val lifted = if (b.removal) granted else blockLifted(pkg, b.reason, policy, now, usage, uncounted)
                    lift = blockLift(lifted, granted, autoOpened[pkg], System.currentTimeMillis())
                    if (lift == BlockLift.OPEN) {
                        // Only open over a screen the child is looking at; a block screen left behind just closes.
                        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                            autoOpened[pkg] = System.currentTimeMillis(); openApp(pkg)
                        } else finish()
                        break
                    }
                }
                delay(2_000)
            }
        }
        val c = harbor
        // Harbor's own screen in the design system: deep water, the brand, the covered app's icon with an amber lock.
        Column(Modifier.fillMaxSize().background(c.s).systemBarsPadding().padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            HarborBrand(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.weight(1f))
            Box(contentAlignment = Alignment.BottomEnd) {
                if (icon != null) Image(icon, null, Modifier.size(84.dp).clip(RoundedCornerShape(22.dp)))
                else HarborLogo(HarborLogoVariant.ICON, 84.dp, ground = c.s2, tile = true, decorative = true)
                Box(Modifier.offset(8.dp, 8.dp).size(38.dp).clip(CircleShape).background(c.s).padding(3.dp).clip(CircleShape).background(c.warn),
                    contentAlignment = Alignment.Center) {
                    HarborIcon(HarborGlyph.LOCK, c.onColor, Modifier.size(18.dp), strokeWidth = 2.2f)
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(title, color = c.ink, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(body, color = c.ink2, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.weight(1f))
            HarborCard {
                when {
                    lift == BlockLift.ANNOUNCE_YES && b.removal -> {
                        Text("Your parent said yes", style = MaterialTheme.typography.titleMedium)
                        Text("You can remove apps for the next 15 minutes.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        HarborButton(::finish, Modifier.fillMaxWidth()) { Text("Continue") }
                    }
                    lift == BlockLift.ANNOUNCE_YES -> {
                        Text("Your parent said yes", style = MaterialTheme.typography.titleMedium)
                        Text("${b.label} is open to you now.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        HarborButton({ openApp(b.packageName) }, Modifier.fillMaxWidth()) { Text("Open ${b.label}") }
                    }
                    lift != BlockLift.NONE -> {
                        Text("${b.label} is allowed again", style = MaterialTheme.typography.titleMedium)
                        Text("Your parent changed the rules.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                        HarborButton({ openApp(b.packageName) }, Modifier.fillMaxWidth()) { Text("Open ${b.label}") }
                    }
                    b.packageName == null || asker == null -> Text("Ask a parent if you think you need this app.",
                        style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    ask == Ask.SENT -> {
                        Text("Asked", style = MaterialTheme.typography.titleMedium)
                        Text("Your parent got a notification. You'll get one too when they answer.", style = MaterialTheme.typography.bodyMedium,
                            color = c.ink2)
                    }
                    ask == Ask.LIMITED -> {
                        Text("You've asked a lot this hour", style = MaterialTheme.typography.titleMedium)
                        Text("Try again a bit later.", style = MaterialTheme.typography.bodyMedium, color = c.ink2)
                    }
                    else -> {
                        Text("Need it for something?", style = MaterialTheme.typography.titleMedium)
                        Text(if (ask == Ask.FAILED) "That didn't send. Check your connection and try again."
                            else "Your parent gets a notification and can say yes or no.",
                            style = MaterialTheme.typography.bodyMedium, color = if (ask == Ask.FAILED) c.dan else c.ink2)
                        HarborButton({
                            ask = Ask.SENDING
                            scope.launch {
                                ask = when (asker.ask(reason, b.packageName, b.label)) {
                                    AskResult.SENT -> Ask.SENT; AskResult.LIMITED -> Ask.LIMITED; AskResult.FAILED -> Ask.FAILED
                                }
                            }
                        }, Modifier.fillMaxWidth(), loading = ask == Ask.SENDING) {
                            Text(if (b.removal) "Ask to remove an app" else askButtonText(reason, b.label))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            HarborOutlinedButton(::goHome, Modifier.fillMaxWidth()) { Text("Go to home screen") }
            Spacer(Modifier.height(10.dp))
            Text("Calls and texts always work.", color = c.ink3, style = MaterialTheme.typography.bodySmall)
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
        /** When a rule change last opened each app from this screen (see [blockLift]). */
        private val autoOpened = java.util.concurrent.ConcurrentHashMap<String, Long>()
        private const val EXTRA_DAILY = "daily"
        private const val EXTRA_REMOVAL = "removal"
        fun intent(context: Context, packageName: String, label: String, reason: BlockReason, daily: Boolean = false, removal: Boolean = false): Intent =
            Intent(context, BlockActivity::class.java)
                .putExtra(EXTRA_PACKAGE, packageName).putExtra(EXTRA_LABEL, label).putExtra(EXTRA_REASON, reason.name).putExtra(EXTRA_DAILY, daily)
                .putExtra(EXTRA_REMOVAL, removal)
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
