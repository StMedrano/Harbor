package dev.stmedrano.harbor.parent.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.stmedrano.harbor.parent.ParentApplication

class ParentMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        // Parents accept only the strict data envelope. The SDK must never
        // bypass current access checks by rendering a server notification body.
        if (message.notification != null || ParentMessageParser.parse(message.data) == null) return
        (application as ParentApplication).queueMessage(message.data)
    }
    override fun onNewToken(token: String) { (application as ParentApplication).queueTokenRefresh() }
}
