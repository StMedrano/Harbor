package dev.stmedrano.harbor.parent.alerts

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat

/** What the phone currently permits for message alerts. Pure data so the truthful state mapping is unit tested. */
data class AlertAccess(val notificationAccess: Boolean, val smsPermission: Boolean)

/**
 * Reads the two special grants message alerts depend on. Notification Access is a Settings toggle (no runtime
 * dialog), rechecked on resume like Usage Access; SMS is a normal runtime permission. Nothing here reads any
 * message; it only reports whether the phone is allowed to observe.
 */
class AlertPermission(context: Context) {
    private val context = context.applicationContext

    fun isNotificationAccessGranted(): Boolean = try {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        val mine = context.packageName
        flat.split(':').any { it.substringBefore('/') == mine }
    } catch (_: Exception) { false }

    fun hasSmsPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED

    fun access(): AlertAccess = AlertAccess(isNotificationAccessGranted(), hasSmsPermission())

    /** Opens the system Notification Access list; the child grants Harbor there. There is no automatic grant. */
    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
}

/**
 * Whether the sender is already a saved contact — a boolean only. No name, number or contact row is returned or
 * retained, and any failure (no permission, no match) is reported as "not a saved contact" rather than throwing.
 */
object SavedContacts {
    fun byNumber(context: Context, number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val uri = android.net.Uri.withAppendedPath(android.provider.ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) { false }
    }

    fun byDisplayName(context: Context, name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return false
        return try {
            val uri = android.provider.ContactsContract.Contacts.CONTENT_URI
            val selection = "${android.provider.ContactsContract.Contacts.DISPLAY_NAME} = ?"
            context.contentResolver.query(uri, arrayOf(android.provider.ContactsContract.Contacts._ID), selection, arrayOf(name), null)?.use { it.moveToFirst() } ?: false
        } catch (_: Exception) { false }
    }
}
