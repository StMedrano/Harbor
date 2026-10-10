package dev.stmedrano.harbor.parent.usage

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.BuildConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsagePlatformTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun guard(){check(BuildConfig.CI_FIXTURE){"Offline fixture only"}}
    private fun appops(mode:String) {
        guard();val descriptor=instrumentation.uiAutomation.executeShellCommand("appops set ${context.packageName} GET_USAGE_STATS $mode")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {it.readBytes()}
    }
    @Test fun permissionManifestAndSettingsRoundTrip() {
        guard()
        val requested=context.packageManager.getPackageInfo(context.packageName,PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(requested.contains(Manifest.permission.PACKAGE_USAGE_STATS));assertFalse(requested.contains("android.permission.QUERY_ALL_PACKAGES"));assertFalse(requested.contains(Manifest.permission.INTERNET))
        val intent=UsagePermission(context).settingsIntent()
        assertEquals(Settings.ACTION_USAGE_ACCESS_SETTINGS,intent.action)
        assertNotNull(context.packageManager.resolveActivity(intent,0))
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        val deadline=SystemClock.uptimeMillis()+5000
        while(instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()!="com.android.settings"&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50)
        assertEquals("com.android.settings",instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString())
        assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        instrumentation.waitForIdleSync()
    }
    @Test fun nativePermissionDenialBlocksReadAndGrantIsObserved() {
        guard()
        try {
            appops("ignore");assertFalse(UsagePermission(context).isGranted())
            val now=System.currentTimeMillis();val window=UsageWindow(now-60000,now,"UTC")
            assertEquals(UsageSourceResult.PermissionDenied,AndroidUsageSource(context).read(window))
            appops("allow");assertTrue(UsagePermission(context).isGranted())
            assertNotEquals(UsageSourceResult.PermissionDenied,AndroidUsageSource(context).read(window))
        } finally {appops("ignore")}
    }
    @Test fun onlyVisibleLaunchableInventoryIsReported() {
        guard()
        val report=AndroidAppInventory(context).read() as InventoryResult.Observed
        assertTrue(report.apps.any{it.packageName==context.packageName});assertTrue(report.apps.size<=500)
        assertEquals(report.apps.size,report.apps.map{it.packageName}.distinct().size)
        val visible=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0).map{it.activityInfo.packageName}.toSet()
        assertTrue(report.apps.all{it.packageName in visible})
    }
}
