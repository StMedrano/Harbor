package dev.stmedrano.harbor.parent.usage

import android.content.Context

interface AppInventorySource { fun read():InventoryResult }
data class AppCandidate(val packageName:String,val label:String?,val visible:Boolean=true,val launchable:Boolean=true)
sealed interface InventoryResult {
    data class Observed(val apps:List<UsageInventoryApp>,val capturedAtMs:Long,val truncated:Boolean):InventoryResult
    data object Unavailable:InventoryResult
}
class AndroidAppInventory(private val query:()->List<AppCandidate>,private val now:()->Long=System::currentTimeMillis):AppInventorySource {
    constructor(context:Context):this({
        val pm=context.applicationContext.packageManager
        pm.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER),0).map {item->
            AppCandidate(item.activityInfo.packageName,try{item.loadLabel(pm)?.toString()}catch(_:Exception){null},launchable=item.activityInfo.enabled&&item.activityInfo.applicationInfo.enabled)
        }
    })
    override fun read():InventoryResult = try {
        val visible=query().filter{it.visible&&it.launchable}
        val valid=visible.filter{it.packageName.length<=255&&Regex("^[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+$").matches(it.packageName)}
        val unique=valid.map {item->
            val label=item.label?.takeIf{it.isNotEmpty()&&it.codePointCount(0,it.length)<=200&&it.none{c->c.code<32||c.code in 127..159}}?:item.packageName
            UsageInventoryApp(item.packageName,label)
        }.groupBy{it.packageName}.map{(_,entries)->entries.minBy{it.label}}.sortedBy{it.packageName}
        InventoryResult.Observed(unique.take(500),now(),unique.size>500||valid.size!=visible.size)
    } catch (_:SecurityException) {InventoryResult.Unavailable}
}
