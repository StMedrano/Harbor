package dev.stmedrano.harbor.parent.usage
import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.BuildConfig
import dev.stmedrano.harbor.parent.profile.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
@RunWith(AndroidJUnit4::class)
class UsageJobTest {
 @Test fun protectedPeriodicJobContainsOnlyValidatedReferences(){
  check(BuildConfig.CI_FIXTURE)
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  val lease=ProfileLease(ProfileRole.CHILD,"22222222-2222-4222-8222-222222222222",7)
  val service=context.packageManager.getServiceInfo(ComponentName(context,UsageJobService::class.java),PackageManager.GET_META_DATA)
  assertFalse(service.exported);assertEquals("android.permission.BIND_JOB_SERVICE",service.permission)
  val info=UsageJobService.info(context,lease)
  assertEquals(4103,info.id);assertTrue(info.isPeriodic);assertEquals(900000L,info.intervalMillis)
  assertFalse(info.isPersisted);assertEquals(setOf("role","ownerId","generation"),info.extras.keySet())
  val refs=info.extras.keySet().associateWith{checkNotNull(info.extras.getString(it))}
  assertEquals(lease,parseUsageJobReferences(refs))
  val scheduler=context.getSystemService(android.app.job.JobScheduler::class.java)
  try {
   assertEquals(android.app.job.JobScheduler.RESULT_SUCCESS,scheduler.schedule(info))
   assertEquals(info.id,scheduler.getPendingJob(4103)?.id)
  }finally {scheduler.cancel(4103)}
 }
}
