package dev.stmedrano.harbor.parent

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.stmedrano.harbor.parent.ui.HarborTheme
import dev.stmedrano.harbor.parent.ui.ParentApp
import dev.stmedrano.harbor.parent.ui.AuthScreen
import dev.stmedrano.harbor.parent.ui.FamilyRoute

class MainActivity : ComponentActivity() {
    private val callback = mutableStateOf<String?>(null)
    private val accountPage = mutableStateOf(true)
    override fun onCreate(savedInstanceState: Bundle?) {
        val requested = scrubCallback(intent)
        super.onCreate(savedInstanceState)
        callback.value = requested
        enableEdgeToEdge()
        setContent {
            val graph = application as ParentApplication
            val identity = graph.authRepository?.identity?.collectAsState()?.value
            HarborTheme {
                ParentApp {
                    if (identity != null) TextButton({ accountPage.value = !accountPage.value }) { Text(if (accountPage.value) "Open family" else "Account") }
                    if (identity == null || accountPage.value || callback.value != null) AuthScreen(graph.authRepository, callback.value) { callback.value = null }
                    else FamilyRoute(checkNotNull(graph.familyViewModel), identity)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        val requested = scrubCallback(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        callback.value = requested
        accountPage.value = true
    }

    private fun scrubCallback(intent: Intent): String? {
        val requested = intent.dataString
        intent.data = null
        intent.clipData = null
        return if (intent.action == Intent.ACTION_VIEW) requested else null
    }
}
