package dev.stmedrano.harbor.parent.notifications

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.stmedrano.harbor.parent.ParentApplication

class FamilyMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (message.notification != null) return
        (application as ParentApplication).queueMessage(message.data)
    }
    override fun onNewToken(token: String) { (application as ParentApplication).queueTokenRefresh() }
}
