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
    constructor(context:Context):this({false},{false},{emptyList()})
    override fun read(window:UsageWindow):UsageSourceResult=UsageSourceResult.Unavailable
}
class UsageEventMapper {
    fun map(atMs:Long,kind:UsageEventKind,packageName:String?,className:String?):UsageEvent?=UsageEvent(atMs,kind,packageName,0)
}
