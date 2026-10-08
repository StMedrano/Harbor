package dev.stmedrano.harbor.parent.notifications

import android.app.Notification
import android.content.Context
import android.content.Intent

class ChildNotificationRenderer(private val context: Context) {
    fun build(route: ParentRoute): Notification = error("Child renderer shape awaiting native behavior proof")
    companion object {
        const val TAP_ACTION = "dev.stmedrano.harbor.parent.CHILD_NOTIFICATION"
        fun consumeTap(intent: Intent): ParentRoute? = null
    }
}
