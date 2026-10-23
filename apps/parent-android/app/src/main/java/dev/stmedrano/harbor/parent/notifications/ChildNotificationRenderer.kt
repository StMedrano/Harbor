package dev.stmedrano.harbor.parent.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Bundle
import dev.stmedrano.harbor.parent.MainActivity
import kotlinx.serialization.json.Json

class ChildNotificationRenderer(private val context: Context) {
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun channelFor(route: ParentRoute) = when (route.kind) {
        CHECKIN_KIND -> CHECKIN_CHANNEL
        APPROVED_KIND, DENIED_KIND -> ANSWER_CHANNEL
        else -> CHANNEL
    }
    fun allowed(route: ParentRoute? = null) = manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(if (route == null) CHANNEL else channelFor(route))?.importance != NotificationManager.IMPORTANCE_NONE
    /**
     * Check-in requests get their own channel: heads-up importance and the phone's notification sound, because a parent is
     * waiting for an answer. Channel importance cannot be raised once a channel exists, so this is a new channel rather than
     * a change to "Phone updates". The user can still turn it down in Android settings.
     */
    private fun ensureChannels() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Phone updates", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(ANSWER_CHANNEL, "Answers from your parent", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a parent says yes or no to more time"
        })
        manager.createNotificationChannel(NotificationChannel(CHECKIN_CHANNEL, "Check-in requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a parent asks you to check in"
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            enableVibration(true)
        })
    }
    fun build(route: ParentRoute): Notification {
        ensureChannels()
        val intent = Intent(context, MainActivity::class.java).setAction(TAP_ACTION).putExtra("route", Json.encodeToString(route))
        val tap = PendingIntent.getActivity(context, route.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val checkIn = route.kind == CHECKIN_KIND
        return Notification.Builder(context, channelFor(route)).setSmallIcon(dev.stmedrano.harbor.parent.R.drawable.ic_stat_harbor)
            .setContentTitle(when (route.kind) { CHECKIN_KIND -> "Check in"; APPROVED_KIND -> "Your parent said yes"; DENIED_KIND -> "Your parent said no"; else -> "Harbor update" })
            .setContentText(when (route.kind) {
                CHECKIN_KIND -> "A parent asked you to check in. Open Harbor to answer."
                APPROVED_KIND -> "You can open the app again for 15 minutes."
                DENIED_KIND -> "Not right now. You can ask again later."
                else -> "Open Harbor to check your phone."
            })
            .apply { if (checkIn) setCategory(Notification.CATEGORY_REMINDER) }
            .setContentIntent(tap).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build()
    }
    fun show(route: ParentRoute) {
        ensureChannels()
        if (allowed(route)) manager.notify(route.hashCode(), build(route))
    }
    fun clear() = manager.cancelAll()
    companion object {
        private const val CHANNEL = "harbor-child-updates"
        private const val CHECKIN_CHANNEL = "harbor-child-checkins"
        private const val ANSWER_CHANNEL = "harbor-child-answers"
        private const val CHECKIN_KIND = "device.checkin.requested"
        private const val APPROVED_KIND = "device.access.approved"
        private const val DENIED_KIND = "device.access.denied"
        const val TAP_ACTION = "dev.stmedrano.harbor.parent.CHILD_NOTIFICATION"
        fun consumeTap(intent: Intent): ParentRoute? {
            val extras = intent.extras
            intent.replaceExtras(null as Bundle?)
            if (intent.action != TAP_ACTION || extras?.keySet() != setOf("route")) return null
            return runCatching { ParentMessageParser.parseRoute(checkNotNull(extras.getString("route"))) }.getOrNull()
        }
    }
}
