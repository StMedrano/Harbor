package dev.stmedrano.harbor.acceptance

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class AcceptanceMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { AcceptanceRuntime.get(this).onToken(token) }
    override fun onMessageReceived(message: RemoteMessage) {
        // FCM's data-only envelope must contain exactly the backend route field.
        if (message.data.keys != setOf("route")) return
        val route = message.data["route"] ?: return
        if (!AcceptanceRuntime.get(this).receipts.record(route, System.currentTimeMillis())) return
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel("acceptance", "Harbor acceptance", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(1, android.app.Notification.Builder(this, "acceptance")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Harbor test notification received")
            .setContentIntent(intent).setAutoCancel(true).build())
    }
}
