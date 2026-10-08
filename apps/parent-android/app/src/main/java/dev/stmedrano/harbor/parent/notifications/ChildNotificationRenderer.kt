package dev.stmedrano.harbor.parent.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import dev.stmedrano.harbor.parent.MainActivity
import kotlinx.serialization.json.Json

class ChildNotificationRenderer(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun allowed() = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun build(route: ParentRoute): Notification {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Phone updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setAction(TAP_ACTION).putExtra("route", Json.encodeToString(route))
        val tap = PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Harbor update").setContentText("Open Harbor to check your phone.")
            .setContentIntent(tap).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }
    fun show(route: ParentRoute) { if (allowed()) manager.notify(route.hashCode(), build(route)) }
    fun clear() = manager.cancelAll()
    companion object {
        private const val CHANNEL = "harbor-child-updates"
        const val TAP_ACTION = "dev.stmedrano.harbor.parent.CHILD_NOTIFICATION"
        fun consumeTap(intent: Intent): ParentRoute? {
            val extras = intent.extras
            intent.replaceExtras(null as Bundle?)
            if (intent.action != TAP_ACTION || extras?.keySet() != setOf("route")) return null
            return runCatching { ParentMessageParser.parseRoute(checkNotNull(extras.getString("route"))) }.getOrNull()
        }
    }
}
