package dev.stmedrano.harbor.parent.notifications

import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.MainActivity
import dev.stmedrano.harbor.parent.ParentApplication
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class FamilyNotificationLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val device = "00000000-0000-4000-8000-000000000001"
    private val child = ProfileLease(ProfileRole.CHILD, device, 7)
    private val route = """{"version":1,"kind":"device.state.changed","familyId":"00000000-0000-4000-8000-000000000002","childId":"00000000-0000-4000-8000-000000000003","deviceId":"$device","resourceId":"00000000-0000-4000-8000-000000000004"}"""
    @Test fun exactlyOneFamilyServiceAndProtectedJobAreInstalled() {
        val context = compose.activity
        val services = context.packageManager.queryIntentServices(
            Intent("com.google.firebase.MESSAGING_EVENT").setPackage(context.packageName), 0)
        // Firebase also packages its low-priority SDK fallback. There must be
        // exactly one app handler, and Android must resolve to that handler.
        assertEquals(listOf(FamilyMessagingService::class.java.name),
            services.map { it.serviceInfo.name }.filter { it.startsWith(context.packageName + ".") })
        assertEquals(FamilyMessagingService::class.java.name, context.packageManager.resolveService(
            Intent("com.google.firebase.MESSAGING_EVENT").setPackage(context.packageName), 0)?.serviceInfo?.name)
        assertEquals("Harbor Family", context.packageManager.getApplicationLabel(context.applicationInfo))
        val service = context.packageManager.getServiceInfo(ComponentName(context, FamilyMessagingService::class.java), 0)
        assertFalse(service.exported)
        val job = context.packageManager.getServiceInfo(ComponentName(context, ParentNotificationJob::class.java), 0)
        assertFalse(job.exported)
        assertEquals("android.permission.BIND_JOB_SERVICE", job.permission)
    }
    @Test fun queuedFamilyWorkContainsOnlyCapturedLeaseAndScopedReferences() {
        val token = ParentNotificationJob.work(child, null, null)
        assertEquals(setOf("role", "ownerId", "generation"), token.intent.extras!!.keySet())
        assertEquals("CHILD", token.intent.getStringExtra("role"))
        assertEquals(device, token.intent.getStringExtra("ownerId"))
        assertEquals(7L, token.intent.getLongExtra("generation", -1))
        val message = ParentNotificationJob.work(child, null, mapOf("route" to route))
        assertEquals(setOf("role", "ownerId", "generation", "route"), message.intent.extras!!.keySet())
        assertEquals(route, message.intent.getStringExtra("route"))
        assertTrue(runCatching { ParentNotificationJob.work(child, null, mapOf("route" to route, "token" to "sensitive")) }.isFailure)
        assertTrue(runCatching { ParentNotificationJob.work(child, null, mapOf("route" to route.replace(device, "00000000-0000-4000-8000-000000000005"))) }.isFailure)
        val owner = ParentIdentity("00000000-0000-4000-8000-000000000006", "00000000-0000-4000-8000-000000000007")
        val parent = ProfileLease(ProfileRole.PARENT, owner.userId, 8)
        val parentToken = ParentNotificationJob.work(parent, owner, null)
        assertEquals(setOf("role", "ownerId", "generation", "userId", "sessionId"), parentToken.intent.extras!!.keySet())
        assertTrue(runCatching { ParentNotificationJob.work(parent, null, null) }.isFailure)
        assertTrue(runCatching { ParentNotificationJob.work(child, owner, null) }.isFailure)
    }
    @Test fun inactiveProfileMustNotPoisonParentRuntimeCache() {
        val graph = compose.activity.application as ParentApplication
        compose.waitUntil(5000) { graph.profiles.state.value != ProfileState.Transitioning }
        assertEquals(ProfileState.Setup, graph.profiles.state.value)
        var ran = false
        runBlocking { graph.accountWork { ran = true } }
        assertFalse(ran)
        val field = ParentApplication::class.java.getDeclaredField("runtime" + '$' + "delegate")
        field.isAccessible = true
        assertFalse((field.get(graph) as Lazy<*>).isInitialized())
        assertNull(graph.profiles.currentLease())
    }
}
