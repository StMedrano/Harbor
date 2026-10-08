package dev.stmedrano.harbor.parent.usage
import android.app.job.*
import android.content.ComponentName
import android.content.Context
import dev.stmedrano.harbor.parent.profile.*
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
 override fun onStartJob(params:JobParameters)=false
 override fun onStopJob(params:JobParameters)=false
 companion object {
  const val JOB_ID=4103
  fun info(context:Context,lease:ProfileLease):JobInfo=JobInfo.Builder(JOB_ID,ComponentName(context,UsageJobService::class.java)).build()
 }
}
