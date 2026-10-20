package dev.stmedrano.harbor.parent.controls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.ui.HarborTheme

/**
 * Harbor's own block screen. It says plainly which rule applies and offers only "Go to home screen". It never imitates
 * a system dialog or another app, and it is kept out of the recent-apps list.
 */
class BlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_LABEL)?.take(64) ?: "This app"
        val reason = intent.getStringExtra(EXTRA_REASON)?.let { runCatching { BlockReason.valueOf(it) }.getOrNull() } ?: BlockReason.BLOCKED_APP
        val (title, body) = blockMessage(reason, label, ControlsBridge.policy)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = goHome() })
        setContent {
            HarborTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Harbor", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(16.dp))
                        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(body, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(32.dp))
                        Button(::goHome, Modifier.fillMaxWidth()) { Text("Go to home screen") }
                    }
                }
            }
        }
    }

    private fun goHome() {
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    companion object {
        private const val EXTRA_LABEL = "label"
        private const val EXTRA_REASON = "reason"
        fun intent(context: Context, label: String, reason: BlockReason): Intent = Intent(context, BlockActivity::class.java)
            .putExtra(EXTRA_LABEL, label).putExtra(EXTRA_REASON, reason.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
    }
}
