package dev.stmedrano.harbor.parent.notifications

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ParentNotificationAndroidTest {
    private val registration = "00000000-0000-4000-8000-000000000001"
    private val route = """{"version":1,"kind":"device.state.changed","familyId":"00000000-0000-4000-8000-000000000002"}"""
    @Test fun tapEnvelopeIsStrictAndClearedBeforeRouting() {
        fun intent() = Intent(ParentNotificationRenderer.TAP_ACTION).putExtra("route", route).putExtra("parentRegistrationId", registration)
        val accepted = intent()
        assertNotNull(ParentNotificationRenderer.consumeTap(accepted))
        assertNull(accepted.extras)
        val rejected = intent().putExtra("token", "synthetic-sensitive")
        assertNull(ParentNotificationRenderer.consumeTap(rejected))
        assertNull(rejected.extras)
        val unsolicited = intent().setAction(Intent.ACTION_VIEW)
        assertNull(ParentNotificationRenderer.consumeTap(unsolicited))
        assertNull(unsolicited.extras)
    }
    @Test fun notificationContainsGenericTextAndAnImmutableExplicitTap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val hint = checkNotNull(ParentMessageParser.parse(mapOf("route" to route, "parentRegistrationId" to registration)))
        val notification = ParentNotificationRenderer(context).build(hint)
        assertEquals("Harbor update", notification.extras.getString("android.title"))
        assertEquals("Open Harbor to check your family.", notification.extras.getString("android.text"))
        assertNotNull(notification.contentIntent)
        assertEquals(context.packageName, notification.contentIntent.creatorPackage)
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(notification.contentIntent.isImmutable)
    }
}
