package dev.stmedrano.harbor.parent.alerts

import android.app.Notification
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
        if (!AlertCaptureBridge.dedup.firstSighting(key)) return

        val extras = post.notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val saved = SavedContacts.byDisplayName(applicationContext, title)
        val alerts = AlertCaptureBridge.core.capture(post.packageName, text, isGroupSummary = false, fromSavedContact = saved)
        AlertCaptureBridge.submit(alerts)
        // `title` and `text` go out of scope here; nothing about the message is retained.
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.key?.let { AlertCaptureBridge.dedup.forget(it) }
    }
}
