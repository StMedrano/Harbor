package dev.stmedrano.harbor.parent.usage
import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
class UsageCollectorTest {
 private val lease=ProfileLease(ProfileRole.CHILD,"22222222-2222-4222-8222-222222222222",1)
 private val now=java.time.Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
 private val saved=UsageStoredState(lease.ownerId,"11111111-1111-4111-8111-111111111111",consent=true)
 private fun inventory()=object:AppInventorySource {override fun read()=InventoryResult.Observed(listOf(UsageInventoryApp("com.example.test","Test")),now,false)}
 @Test fun onlyConsentingCurrentChildCanQuery()=runTest {
  var reads=0
  val source=object:UsageSource {override fun read(window:UsageWindow):UsageSourceResult {reads++;return UsageSourceResult.Unavailable}}
  val c=UsageCollector(source,inventory(),{true},{now},{"UTC"},{emptySet()})
  for(pair in listOf(lease.copy(role=ProfileRole.PARENT) to saved,lease to saved.copy(consent=false),lease to saved.copy(bindingId="foreign"))) {
   try{c.collect(pair.first,pair.second);fail("unauthorized collection") }catch(_:IllegalStateException){}
  }
  assertEquals(0,reads)
 }
 @Test fun observedIntervalsAndInventoryReconcileWithoutDoubleCounting()=runTest {
  val source=object:UsageSource {override fun read(window:UsageWindow)=UsageSourceResult.Observed(listOf(
   UsageEvent(now-90000,UsageEventKind.SCREEN_ON,null,null),UsageEvent(now-90000,UsageEventKind.UNLOCKED,null,null),
   UsageEvent(now-60000,UsageEventKind.RESUMED,"com.example.test",1),UsageEvent(now,UsageEventKind.PAUSED,"com.example.test",1))) }
  val c=UsageCollector(source,inventory(),{it==lease},{now},{"UTC"},{emptySet()})
  val first=c.collect(lease,saved)
  assertEquals(60000L,first.report.days.last().totalMs)
  assertEquals(7,first.report.days.size);assertEquals("Test",first.report.inventory.single().label)
  val repeat=c.collect(lease,saved.copy(latestAggregate=first.aggregate))
  assertEquals(first.report.days,repeat.report.days)
  validateUsageReport(repeat.report,now)
 }
 @Test fun unavailableAndDeniedAreNotZeroAndLockedDoesNotEnumerate()=runTest {
  var inventoryReads=0
  val inv=object:AppInventorySource {override fun read():InventoryResult {inventoryReads++;return InventoryResult.Unavailable}}
  for(result in listOf(UsageSourceResult.Unavailable,UsageSourceResult.PermissionDenied,UsageSourceResult.UserLocked)) {
   val c=UsageCollector(object:UsageSource {override fun read(window:UsageWindow)=result},inv,{true},{now},{"UTC"},{emptySet()})
   val data=c.collect(lease,saved)
   assertTrue(data.report.days.all{it.totalMs==null&&it.apps.isEmpty()})
   assertEquals(InventoryStatus.UNAVAILABLE,data.report.inventoryStatus)
   validateUsageReport(data.report,now)
  }
  assertEquals(1,inventoryReads)
 }
 @Test fun generationChangeAfterPlatformReadStopsBeforeInventory()=runTest {
  var current=true;var reads=0
  val source=object:UsageSource {override fun read(window:UsageWindow):UsageSourceResult {current=false;return UsageSourceResult.Unavailable}}
  val inv=object:AppInventorySource {override fun read():InventoryResult {reads++;return InventoryResult.Unavailable}}
  val c=UsageCollector(source,inv,{current},{now},{"UTC"},{emptySet()})
  try{c.collect(lease,saved);fail("stale inventory queried")}catch(_:IllegalStateException){}
  assertEquals(0,reads)
 }
}
