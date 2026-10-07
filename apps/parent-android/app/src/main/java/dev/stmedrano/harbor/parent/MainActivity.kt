package dev.stmedrano.harbor.parent

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.stmedrano.harbor.parent.ui.HarborTheme
import dev.stmedrano.harbor.parent.ui.ParentApp
import dev.stmedrano.harbor.parent.ui.AuthScreen

class MainActivity : ComponentActivity() {
    private val callback = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        val requested = scrubCallback(intent)
        super.onCreate(savedInstanceState)
        callback.value = requested
        enableEdgeToEdge()
        setContent {
            HarborTheme {
                ParentApp { AuthScreen((application as ParentApplication).authRepository, callback.value) { callback.value = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        val requested = scrubCallback(intent)
        super.onNewIntent(intent)
        setIntent(intent)
        callback.value = requested
    }

    private fun scrubCallback(intent: Intent): String? {
        val requested = intent.dataString
        intent.data = null
        intent.clipData = null
        return if (intent.action == Intent.ACTION_VIEW) requested else null
    }
}
