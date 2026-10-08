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

class ParentNotificationRenderer(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    fun allowed(): Boolean = manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    fun build(hint: ParentHint): Notification {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Family updates", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(context, MainActivity::class.java).setAction(TAP_ACTION)
            .putExtra("route", Json.encodeToString(hint.route)).putExtra("parentRegistrationId", hint.registrationId)
        val tap = PendingIntent.getActivity(context, hint.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Harbor update").setContentText("Open Harbor to check your family.")
            .setContentIntent(tap).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }
    fun show(hint: ParentHint) { if (allowed()) manager.notify(hint.hashCode(), build(hint)) }
    fun clear() = manager.cancelAll()
    companion object {
        private const val CHANNEL = "harbor-parent-updates"
        const val TAP_ACTION = "dev.stmedrano.harbor.parent.NOTIFICATION"
        fun consumeTap(intent: Intent): ParentHint? {
            val extras = intent.extras
            intent.replaceExtras(null as Bundle?)
            if (intent.action != TAP_ACTION || extras == null) return null
            val keys = extras.keySet()
            if (keys != setOf("route", "parentRegistrationId")) return null
            return runCatching { ParentMessageParser.parse(keys.associateWith { checkNotNull(extras.getString(it)) }) }.getOrNull()
        }
    }
}
