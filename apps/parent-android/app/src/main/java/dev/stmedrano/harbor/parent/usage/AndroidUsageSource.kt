package dev.stmedrano.harbor.parent.usage

import android.content.Context

interface UsageSource { fun read(window:UsageWindow):UsageSourceResult }
sealed interface UsageSourceResult {
    data class Observed(val events:List<UsageEvent>):UsageSourceResult
    data object PermissionDenied:UsageSourceResult
    data object UserLocked:UsageSourceResult
    data object Unavailable:UsageSourceResult
}
class AndroidUsageSource(private val granted:()->Boolean,private val unlocked:()->Boolean,private val reader:(UsageWindow)->List<UsageEvent>?):UsageSource {
    constructor(context:Context):this(UsagePermission(context)::isGranted,
        {context.applicationContext.getSystemService(android.os.UserManager::class.java)?.isUserUnlocked==true},
        {window->readPlatformUsage(context.applicationContext,window)})
    override fun read(window:UsageWindow):UsageSourceResult = try {
        require(window.startMs<window.endMs)
        if(!granted()) UsageSourceResult.PermissionDenied
        else if(!unlocked()) UsageSourceResult.UserLocked
        else {
            val events=reader(window)
            when {
                !granted()->UsageSourceResult.PermissionDenied
                !unlocked()->UsageSourceResult.UserLocked
                events.isNullOrEmpty()->UsageSourceResult.Unavailable
                else->UsageSourceResult.Observed(events)
            }
        }
    } catch (_:SecurityException) {UsageSourceResult.Unavailable}
}
class UsageEventMapper {
    private val classes=mutableMapOf<Pair<String,String?>,Int>()
    fun map(atMs:Long,kind:UsageEventKind,packageName:String?,className:String?):UsageEvent? {
        val activity=kind in setOf(UsageEventKind.RESUMED,UsageEventKind.PAUSED,UsageEventKind.STOPPED)
        if(!activity)return UsageEvent(atMs,kind,null,null)
        if(packageName.isNullOrBlank())return UsageEvent(atMs,UsageEventKind.CLOCK_GAP,null,null,false)
        val id=classes.getOrPut(packageName to className){classes.size+1}
        return UsageEvent(atMs,kind,packageName,id,false)
    }
}

private fun readPlatformUsage(context:Context,window:UsageWindow):List<UsageEvent>? {
    val manager=context.getSystemService(android.app.usage.UsageStatsManager::class.java)?:return null
    val stream=manager.queryEvents(maxOf(0,window.startMs-3*86400000L),window.endMs)?:return null
    val event=android.app.usage.UsageEvents.Event()
    val mapper=UsageEventMapper()
    val result=mutableListOf<UsageEvent>()
    var seen=0
    while(stream.hasNextEvent()) {
        if(++seen>50000)return null // Unknown, never silently assume the missing tail is zero.
        stream.getNextEvent(event)
        val kind=when(event.eventType) {
            android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED->UsageEventKind.RESUMED
            android.app.usage.UsageEvents.Event.ACTIVITY_PAUSED->UsageEventKind.PAUSED
            android.app.usage.UsageEvents.Event.ACTIVITY_STOPPED->UsageEventKind.STOPPED
            android.app.usage.UsageEvents.Event.SCREEN_INTERACTIVE->UsageEventKind.SCREEN_ON
            android.app.usage.UsageEvents.Event.SCREEN_NON_INTERACTIVE->UsageEventKind.SCREEN_OFF
            android.app.usage.UsageEvents.Event.KEYGUARD_HIDDEN->UsageEventKind.UNLOCKED
            android.app.usage.UsageEvents.Event.KEYGUARD_SHOWN->UsageEventKind.LOCKED
            android.app.usage.UsageEvents.Event.DEVICE_STARTUP->UsageEventKind.STARTUP
            android.app.usage.UsageEvents.Event.DEVICE_SHUTDOWN->UsageEventKind.SHUTDOWN
            else->null
        }?:continue
        mapper.map(event.timeStamp,kind,event.packageName,event.className)?.let{result.add(it)}
    }
    return result
}

fun resolvedHomePackages(context:Context):Set<String> {
    val home=context.applicationContext.packageManager.resolveActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),0)?.activityInfo?.packageName
    return setOf("com.android.systemui")+listOfNotNull(home)
}