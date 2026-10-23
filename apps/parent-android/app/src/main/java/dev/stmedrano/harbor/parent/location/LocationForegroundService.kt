package dev.stmedrano.harbor.parent.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import dev.stmedrano.harbor.parent.MainActivity
import dev.stmedrano.harbor.parent.ParentApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps location sharing alive while the app is closed. It is visible to the child through a persistent notification, and
 * it stops itself when this phone is no longer a paired child device. Android requires the notification; it is also the
 * honest signal that location is being shared.
 */
class LocationForegroundService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: Exception) {
            // Not allowed to run a location service right now (permission withdrawn, started from the background, ...).
            stopSelf(); return START_NOT_STICKY
        }
        scope.launch {
            val running = (application as ParentApplication).superviseLocationFromService()
            if (!running) stopSelf()
        }
        return START_STICKY
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        const val CHANNEL_ID = "harbor-location-sharing"
        private const val NOTIFICATION_ID = 4210
        fun start(context: Context): Boolean = try {
            context.startForegroundService(Intent(context, LocationForegroundService::class.java)); true
        } catch (_: Exception) { false }
        fun stop(context: Context): Boolean = try { context.stopService(Intent(context, LocationForegroundService::class.java)); true } catch (_: Exception) { false }

        private fun notification(context: Context): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone shares its location with your parents."
                setShowBadge(false)
            })
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            return Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(dev.stmedrano.harbor.parent.R.drawable.ic_stat_harbor)
                .setContentTitle("Harbor Family is sharing this phone's location")
                .setContentText("Your parents can see where this phone is.")
                .setOngoing(true).setContentIntent(open).setCategory(Notification.CATEGORY_SERVICE).build()
        }
    }
}
