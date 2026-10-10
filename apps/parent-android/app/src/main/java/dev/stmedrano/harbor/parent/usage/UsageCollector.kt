package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Instant
import java.time.ZoneId

fun unavailableUsageReport(nowMs:Long,zoneId:String,permission:UsagePermissionState=UsagePermissionState.UNAVAILABLE)=
 UsageReportV1(1,"00000000-0000-4000-8000-000000000000",1,Instant.ofEpochMilli(nowMs).toString(),zoneId,permission,InventoryStatus.UNAVAILABLE,emptyList(),emptyList())
class UsageCollector(private val source:UsageSource,private val inventory:AppInventorySource,private val current:(ProfileLease)->Boolean,
 private val now:()->Long,private val zone:()->String,private val homes:()->Set<String>) {
 private suspend fun checked(lease:ProfileLease){currentCoroutineContext().ensureActive();check(lease.role==ProfileRole.CHILD&&current(lease)){"Current child required"}}
 suspend fun collect(lease:ProfileLease,saved:UsageStoredState):UsageCollection {
  checked(lease);check(saved.consent&&saved.bindingId==lease.ownerId){"Usage consent required"}
  val at=now();val zoneId=zone();val start=Instant.ofEpochMilli(at).atZone(ZoneId.of(zoneId)).toLocalDate().minusDays(6).atStartOfDay(ZoneId.of(zoneId)).toInstant().toEpochMilli()
  val window=UsageWindow(start,at,zoneId,homes()+"com.android.systemui")
  checked(lease)
  val result=source.read(window);checked(lease)
  if(result==UsageSourceResult.PermissionDenied)return UsageCollection(unavailableUsageReport(at,zoneId,UsagePermissionState.DENIED),null)
  if(result==UsageSourceResult.UserLocked)return UsageCollection(unavailableUsageReport(at,zoneId),null)
  // System surfaces such as the share sheet report the package "android", which the report contract cannot carry: they are not an app, so they are not counted.
  val observed=(result as? UsageSourceResult.Observed)?.events.orEmpty().filter{it.packageName==null||isReportablePackage(it.packageName)}
  val aggregate=reconcileUsage(saved.latestAggregate,observed,window)
  checked(lease);val apps=inventory.read();checked(lease)
  val status=when(apps){is InventoryResult.Observed->if(apps.truncated)InventoryStatus.TRUNCATED else InventoryStatus.COMPLETE;else->InventoryStatus.UNAVAILABLE}
  val report=UsageReportV1(1,saved.epochId,saved.nextSequence,Instant.ofEpochMilli(at).toString(),zoneId,UsagePermissionState.GRANTED,status,(apps as? InventoryResult.Observed)?.apps.orEmpty(),aggregate.days)
  return UsageCollection(validateUsageReport(report,at),aggregate)
 }
}
