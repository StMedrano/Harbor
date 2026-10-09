package dev.stmedrano.harbor.parent.usage

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

enum class UsageEventKind { RESUMED,PAUSED,STOPPED,SCREEN_ON,SCREEN_OFF,UNLOCKED,LOCKED,STARTUP,SHUTDOWN,CLOCK_GAP }
data class UsageEvent(val atMs:Long,val kind:UsageEventKind,val packageName:String?,val instanceId:Int?,val identityKnown:Boolean=true)
@Serializable data class UsageWindow(val startMs:Long,val endMs:Long,val zoneId:String,val excludedPackages:Set<String> = setOf("com.android.systemui"))
/** Aggregated observed interval: no raw activity class or event is retained. */
@Serializable data class UsageSlice(val startMs:Long,val endMs:Long,val packages:Set<String>,val complete:Boolean)
@Serializable data class UsageCoverage(val slices:List<UsageSlice>)
@Serializable data class UsageReduction(val days:List<UsageDay>,val coverage:UsageCoverage,val window:UsageWindow,val partialDates:Set<String> = emptySet())

fun reduceUsage(events:List<UsageEvent>,window:UsageWindow):UsageReduction {
    require(window.startMs<window.endMs)
    val active=linkedSetOf<Pair<String,Int>>()
    var interactive:Boolean?=null;var unlocked:Boolean?=null;var activeKnown=false
    var cursor=window.startMs
    val slices=mutableListOf<UsageSlice>()
    fun account(until:Long) {
        val end=minOf(until,window.endMs)
        if(end<=cursor)return
        if(interactive==false||unlocked==false) slices.add(UsageSlice(cursor,end,emptySet(),true))
        else if(interactive==true&&unlocked==true&&(activeKnown||active.isNotEmpty())) {
            slices.add(UsageSlice(cursor,end,active.map{it.first}.toSet()-window.excludedPackages,activeKnown))
        }
        cursor=end
    }
    for(e in events.distinct().sortedBy{it.atMs}) {
        if(e.atMs>window.endMs)break
        if(e.atMs>window.startMs) {
            // A boot without a logged shutdown or a clock jump hides an unknown stretch: skip it instead of crediting it to the last app.
            if(e.kind==UsageEventKind.STARTUP||e.kind==UsageEventKind.CLOCK_GAP)cursor=maxOf(cursor,minOf(e.atMs,window.endMs))
            else account(e.atMs)
        }
        when(e.kind) {
            UsageEventKind.SCREEN_OFF->{interactive=false;active.clear();activeKnown=true}
            UsageEventKind.LOCKED->{unlocked=false;active.clear();activeKnown=true}
            UsageEventKind.SCREEN_ON->interactive=true
            UsageEventKind.UNLOCKED->unlocked=true
            UsageEventKind.RESUMED->e.packageName?.let{active.add(it to (e.instanceId?:0));if(!e.identityKnown)activeKnown=false}
            UsageEventKind.PAUSED,UsageEventKind.STOPPED->e.packageName?.let{active.remove(it to (e.instanceId?:0))}
            UsageEventKind.STARTUP,UsageEventKind.SHUTDOWN,UsageEventKind.CLOCK_GAP->{
                active.clear();activeKnown=false;interactive=null;unlocked=null
            }
        }
    }
    account(window.endMs)
    return reduction(slices,window,emptySet())
}

fun reconcileUsage(previous:UsageReduction?,events:List<UsageEvent>,window:UsageWindow):UsageReduction {
    val fresh=reduceUsage(events,window)
    if(previous==null)return fresh
    if(previous.window.zoneId!=window.zoneId||previous.window.excludedPackages!=window.excludedPackages) {
        val date=Instant.ofEpochMilli(window.endMs).atZone(ZoneId.of(window.zoneId)).toLocalDate().toString()
        return reduction(fresh.coverage.slices,window,setOf(date))
    }
    val retained=mutableListOf<UsageSlice>()
    val replacements=fresh.coverage.slices
    var next=0
    for(original in previous.coverage.slices) {
        val old=clip(original,window.startMs,window.endMs)?:continue
        var cursor=old.startMs
        while(next<replacements.size&&replacements[next].endMs<=cursor)next++
        var index=next
        while(index<replacements.size&&replacements[index].startMs<old.endMs) {
            val replace=replacements[index]
            clip(old,cursor,replace.startMs)?.let{retained.add(it)}
            cursor=maxOf(cursor,replace.endMs)
            if(cursor>=old.endMs)break
            index++
        }
        clip(old,cursor,old.endMs)?.let{retained.add(it)}
    }
    return reduction(retained+fresh.coverage.slices,window,previous.partialDates)
}

private fun clip(s:UsageSlice,start:Long,end:Long):UsageSlice? {
    val a=maxOf(s.startMs,start);val b=minOf(s.endMs,end)
    return if(a<b)s.copy(startMs=a,endMs=b) else null
}
private fun reduction(source:List<UsageSlice>,window:UsageWindow,partial:Set<String>):UsageReduction {
    val zone=ZoneId.of(window.zoneId)
    val first=Instant.ofEpochMilli(window.startMs).atZone(zone).toLocalDate()
    val last=Instant.ofEpochMilli(window.endMs).atZone(zone).toLocalDate()
    require(ChronoUnit.DAYS.between(first,last) in 0..6)
    val normalized=mutableListOf<UsageSlice>()
    for(s in source.sortedBy{it.startMs}) {
        val old=normalized.lastOrNull()
        require(old==null||old.endMs<=s.startMs)
        if(old!=null&&old.endMs==s.startMs&&old.packages==s.packages&&old.complete==s.complete) normalized[normalized.lastIndex]=old.copy(endMs=s.endMs)
        else normalized.add(s)
    }
    val dropped=normalized.dropLast(20000)
    val slices=normalized.takeLast(20000)
    val affected=partial.toMutableSet()
    for(s in dropped) {
        var d=Instant.ofEpochMilli(s.startMs).atZone(zone).toLocalDate()
        val end=Instant.ofEpochMilli(s.endMs-1).atZone(zone).toLocalDate()
        while(d<=end){affected.add(d.toString());d=d.plusDays(1)}
    }
    val days=mutableListOf<UsageDay>()
    var day:LocalDate=first
    while(day<=last) {
        val start=day.atStartOfDay(zone).toInstant().toEpochMilli()
        val end=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val through=minOf(window.endMs,end)
        val observed=slices.mapNotNull{clip(it,maxOf(start,window.startMs),through)}
        val apps=linkedMapOf<String,Long>()
        var total=0L;var covered=start;var complete=day.toString() !in affected
        for(s in observed) {
            if(s.startMs!=covered||!s.complete)complete=false
            covered=s.endMs
            val length=s.endMs-s.startMs
            if(s.packages.isNotEmpty())total+=length
            for(p in s.packages)apps[p]=(apps[p]?:0L)+length
        }
        if(covered!=through)complete=false
        val allApps=apps.entries.sortedBy{it.key}
        
        if(allApps.size>500)complete=false
        val quality=when {observed.isEmpty()->UsageQuality.UNAVAILABLE;complete->UsageQuality.OBSERVED;else->UsageQuality.PARTIAL}
        days.add(UsageDay(day.toString(),Instant.ofEpochMilli(start).toString(),Instant.ofEpochMilli(end).toString(),Instant.ofEpochMilli(through).toString(),observed.firstOrNull()?.let{Instant.ofEpochMilli(it.startMs).toString()},quality,if(observed.isEmpty())null else total,allApps.take(500).map{UsageApp(it.key,it.value)}))
        day=day.plusDays(1)
    }
    return UsageReduction(days,UsageCoverage(slices),window,affected.filterTo(mutableSetOf()){it>=first.toString()&&it<=last.toString()})
}
