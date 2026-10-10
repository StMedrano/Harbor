package dev.stmedrano.harbor.parent.usage
import android.app.job.*
import android.content.ComponentName
import android.content.Context
import dev.stmedrano.harbor.parent.profile.*
import dev.stmedrano.harbor.parent.ParentApplication
import kotlinx.coroutines.*
import android.os.PersistableBundle
fun usageJobReferences(lease:ProfileLease):Map<String,String> {
 val values=mapOf("role" to lease.role.name,"ownerId" to lease.ownerId,"generation" to lease.generation.toString())
 parseUsageJobReferences(values);return values
}
fun parseUsageJobReferences(values:Map<String,String>):ProfileLease {
 require(values.keys==setOf("role","ownerId","generation")&&values["role"]=="CHILD")
 val owner=checkNotNull(values["ownerId"])
 require(runCatching{java.util.UUID.fromString(owner).toString().equals(owner,true)}.getOrDefault(false))
 val generation=values["generation"]?.toLongOrNull()
 require(generation!=null&&generation>0&&generation.toString()==values["generation"])
 return ProfileLease(ProfileRole.CHILD,owner,generation)
}
class UsageJobService:JobService(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private val running=mutableMapOf<Int,Job>()
 override fun onStartJob(params:JobParameters):Boolean {
  val execution=scope.launch(start=CoroutineStart.LAZY) {
   try {
    val refs=params.extras.keySet().associateWith{checkNotNull(params.extras.getString(it))}
    val lease=parseUsageJobReferences(refs)
    withTimeout(15000){(application as ParentApplication).processUsageBackground(lease)}
   }catch(_:TimeoutCancellationException){/* No receipt is inferred from a timed-out attempt. */}
   catch(cancelled:CancellationException){throw cancelled}
   catch(_:Exception){/* Foreground refresh remains available; retained pending bytes are unconfirmed. */}
   finally {
    if(running[params.jobId]==currentCoroutineContext()[Job]){running.remove(params.jobId);jobFinished(params,false)}
   }
  }
  running[params.jobId]=execution;execution.start();return true
 }
 override fun onStopJob(params:JobParameters):Boolean {running.remove(params.jobId)?.cancel();return false}
 override fun onDestroy(){scope.cancel();super.onDestroy()}
 companion object {
  const val JOB_ID=4103
  fun info(context:Context,lease:ProfileLease):JobInfo {
   val extras=PersistableBundle().apply{usageJobReferences(lease).forEach{(key,value)->putString(key,value)}}
   return JobInfo.Builder(JOB_ID,ComponentName(context,UsageJobService::class.java)).setExtras(extras).setPeriodic(900000L)
    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(false).build()
  }
  fun schedule(context:Context,lease:ProfileLease)=context.getSystemService(JobScheduler::class.java).schedule(info(context,lease))==JobScheduler.RESULT_SUCCESS
  fun cancel(context:Context){context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)}
 }
}

