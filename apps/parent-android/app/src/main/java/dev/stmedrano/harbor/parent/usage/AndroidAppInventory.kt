package dev.stmedrano.harbor.parent.usage

import android.content.Context

interface AppInventorySource { fun read():InventoryResult }
data class AppCandidate(val packageName:String,val label:String?,val visible:Boolean=true,val launchable:Boolean=true)
sealed interface InventoryResult {
    data class Observed(val apps:List<UsageInventoryApp>,val capturedAtMs:Long,val truncated:Boolean):InventoryResult
    data object Unavailable:InventoryResult
}
class AndroidAppInventory(private val query:()->List<AppCandidate>,private val now:()->Long=System::currentTimeMillis):AppInventorySource {
    constructor(context:Context):this({emptyList()})
    override fun read():InventoryResult=InventoryResult.Unavailable
}
