package dev.stmedrano.harbor.parent.alerts

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Inspects notifications only from the [MonitoredApps] allowlist. The package is checked before any text is touched;
 * non-allowlisted apps and group-summary posts are dropped immediately. Text from an allowlisted post is read into a
 * local, classified through the pure core and discarded in the same method — it is never logged, stored or sent.
 * Only the text-free alerts reach the runtime. The service cannot be bound by other apps (it requires the system
 * BIND_NOTIFICATION_LISTENER_SERVICE permission, declared in the manifest).
 */
class HarborNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val post = sbn ?: return
        // Allowlist gate first: unmonitored apps are ignored before their content is read.
        if (!MonitoredApps.isMonitored(post.packageName)) return
        if ((post.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val key = post.key ?: return
        // A chat keeps one notification and updates it for every new message, so the key alone would hide every
        // message after the first. The post time changes with each update and repeats only on a re-delivery.
        if (!AlertCaptureBridge.dedup.firstSighting("$key|${post.postTime}")) return

        val extras = post.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val newest = newestChatMessage(extras)
        val texts = listOf(
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            newest?.first,
        )
        val saved = SavedContacts.byDisplayName(applicationContext, newest?.second ?: title)
        val alerts = AlertCaptureBridge.core.captureAny(post.packageName, texts, isGroupSummary = false, fromSavedContact = saved)
        AlertCaptureBridge.submit(alerts)
        // The texts go out of scope here; nothing about the message is retained.
    }

    /** The newest message of a chat-style notification as (text, sender name). Only the last one: older ones were already seen. */
    @Suppress("DEPRECATION")
    private fun newestChatMessage(extras: Bundle): Pair<String?, String?>? {
        val last = try { extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.lastOrNull() as? Bundle } catch (_: Exception) { null } ?: return null
        return last.getCharSequence("text")?.toString() to last.getCharSequence("sender")?.toString()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Keys are remembered together with the post time, so there is nothing to forget by key alone.
        Unit
    }
}
