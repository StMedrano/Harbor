package dev.stmedrano.harbor.parent.notifications

import android.app.job.*
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import org.junit.Assert.*
import org.junit.Test

class ParentNotificationJobTest {
    @Test fun nativeJobUsesProtectedServiceAndQueuesReferencesWithoutProviderOrAuthTokens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = ParentIdentity("00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000002")
        val service = context.packageManager.getServiceInfo(ComponentName(context, ParentNotificationJob::class.java), 0)
        assertFalse(service.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", service.permission)
        val info = ParentNotificationJob.info(context)
        assertNotNull(info.requiredNetwork)
        assertFalse(info.isPersisted)
        val tokenWork = ParentNotificationJob.work(owner, null)
        assertEquals(setOf("userId", "sessionId"), tokenWork.intent.extras!!.keySet())
        val route = """{"version":1,"kind":"device.state.changed","familyId":"00000000-0000-4000-8000-000000000003","childId":"00000000-0000-4000-8000-000000000004","deviceId":"00000000-0000-4000-8000-000000000005","resourceId":"00000000-0000-4000-8000-000000000006"}"""
        val data = mapOf("route" to route, "parentRegistrationId" to "00000000-0000-4000-8000-000000000007")
        val messageWork = ParentNotificationJob.work(owner, data)
        assertEquals(setOf("userId", "sessionId", "route", "parentRegistrationId"), messageWork.intent.extras!!.keySet())
        assertEquals(route, messageWork.intent.getStringExtra("route"))
        assertTrue(runCatching { ParentNotificationJob.work(owner, data + ("token" to "synthetic-sensitive")) }.isFailure)
        try { assertTrue(ParentNotificationJob.enqueue(context, owner, null)) }
        finally { context.getSystemService(JobScheduler::class.java).cancel(ParentNotificationJob.JOB_ID) }
    }
}
