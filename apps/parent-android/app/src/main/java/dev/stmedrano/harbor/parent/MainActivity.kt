package dev.stmedrano.harbor.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import dev.stmedrano.harbor.parent.ui.HarborTheme
import dev.stmedrano.harbor.parent.ui.ParentApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HarborTheme { ParentApp { Text(if (BuildConfig.CI_FIXTURE) "Development preview · offline fixture" else "Parent sign-in is being prepared.") } }
        }
    }
}
