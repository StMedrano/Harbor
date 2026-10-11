package dev.stmedrano.harbor.parent.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.stmedrano.harbor.parent.ParentApplication
import kotlinx.coroutines.launch

/** Re-arms location sharing after a restart or an app update. It only evaluates the same rules as the app does when opened. */
class LocationBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext as ParentApplication
        val pending = goAsync()
        app.accountScope.launch {
            try { app.superviseLocationFromBoot() } finally { pending.finish() }
        }
    }
}
