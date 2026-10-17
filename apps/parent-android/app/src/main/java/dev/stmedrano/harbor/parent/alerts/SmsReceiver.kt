package dev.stmedrano.harbor.parent.alerts

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

/**
 * Classifies incoming SMS on-device. Message parts are assembled in memory, classified through the pure core and
 * discarded here; the body is never logged, stored or sent. Only text-free alerts reach the runtime. The sender
 * number is used solely for a boolean saved-contact check and is not retained.
 */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val ctx = context?.applicationContext ?: return
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val messages = try { Telephony.Sms.Intents.getMessagesFromIntent(intent) } catch (_: Exception) { return } ?: return
        if (messages.isEmpty()) return
        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        val sender = messages.firstOrNull()?.displayOriginatingAddress
        val saved = SavedContacts.byNumber(ctx, sender)
        val alerts = AlertCaptureBridge.core.captureSms(body, fromSavedContact = saved)
        AlertCaptureBridge.submit(alerts)
    }
}
