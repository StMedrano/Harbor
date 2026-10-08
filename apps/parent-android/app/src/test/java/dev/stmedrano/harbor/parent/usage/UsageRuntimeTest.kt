package dev.stmedrano.harbor.parent.usage
import dev.stmedrano.harbor.parent.auth.*
import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsageRuntimeTest {
 private val lease=ProfileLease(ProfileRole.CHILD,"22222222-2222-4222-8222-222222222222",1)
 private val now=java.time.Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
 private val report=Json.decodeFromString<UsageReportV1>(checkNotNull(javaClass.classLoader?.getResource("usage-report-v1.json")).readText())
 private val denied=report.copy(usagePermission=UsagePermissionState.DENIED,inventoryStatus=InventoryStatus.UNAVAILABLE,inventory=emptyList(),days=emptyList())
 private class Values:AuthValues {val data=mutableMapOf<String,String>();override fun read(key:String)=data[key];override fun write(key:String,value:String?){if(value==null)data.remove(key)else data[key]=value};override fun clear(){data.clear()}}
 private val values=Values()
 private val history=object:UsageHistory {var value:UsageHistoryState?=null;override fun read()=value;override fun write(value:UsageHistoryState){this.value=value};override fun clear(){value=null}}
 private val cipher=object:AuthCipher {override fun encrypt(slot:String,value:ByteArray)=value.reversedArray();override fun decrypt(slot:String,value:ByteArray)=value.reversedArray()}
 private fun store()=EncryptedUsageStore(values,cipher,history,{now})
 private fun reply(seq:Long)="{\"confirmed\":true,\"sequence\":$seq,\"receivedAt\":\"2026-10-08T12:00:00Z\"}"
 @Test fun parentAndMissingConsentNeverEnumerateOrSend()=runTest {
  var reads=0;var sends=0
  val s=store();val r=UsageRuntime(backgroundScope,s,UsageReporter({true},{_,_,_->sends++;error("unexpected send")}),{true},{true},{_,_->reads++;UsageCollection(report,null)},{denied})
  r.start(lease.copy(role=ProfileRole.PARENT));r.refresh(lease.copy(role=ProfileRole.PARENT))
  r.start(lease);r.refresh(lease)
  assertEquals(0,reads);assertEquals(0,sends);assertNull(s.read(lease.ownerId))
 }
 @Test fun permissionLostAfterEnumerationDiscardsMetricsAndAdvancesAboveOldPending()=runTest {
  var permission=true;val s=store();s.update(lease.ownerId){it.copy(consent=true)};s.newPendingReport(lease.ownerId,report)
  var sent:UsageReportV1?=null
  val sender=UsageReporter({it==lease},{_,_,body->Json.decodeFromString<UsageReportV1>(body).let{sent=it;reply(it.sequence)}})
  val r=UsageRuntime(backgroundScope,s,sender,{it==lease},{permission},{_,_->permission=false;UsageCollection(report,null)},{denied})
  r.start(lease);r.refresh(lease)
  assertEquals(2L,sent?.sequence);assertEquals(UsagePermissionState.DENIED,sent?.usagePermission)
  assertTrue(checkNotNull(sent).days.isEmpty());assertNull(s.read(lease.ownerId)?.latestAggregate)
 }
 @Test fun generationChangeDuringCollectionCannotQueueOrUpload()=runTest {
  var current=true;var sends=0;val s=store();s.update(lease.ownerId){it.copy(consent=true)}
  val sender=UsageReporter({current},{_,_,_->sends++;reply(1)})
  val r=UsageRuntime(backgroundScope,s,sender,{current},{true},{_,_->current=false;UsageCollection(report,null)},{denied})
  r.start(lease);r.refresh(lease)
  assertEquals(0,sends);assertNull(s.read(lease.ownerId)?.pending)
 }
 @Test fun optOutCancelsCollectorThenRetainsHigherClearOffline()=runTest {
  val s=store();s.update(lease.ownerId){it.copy(consent=true)};s.newPendingReport(lease.ownerId,report)
  val entered=CompletableDeferred<Unit>();var cancelled=false;val ops=mutableListOf<String>()
  val sender=UsageReporter({it==lease},{_,op,_->ops+=op;throw java.io.IOException("offline")})
  val r=UsageRuntime(backgroundScope,s,sender,{it==lease},{true},{_,_->entered.complete(Unit);try{awaitCancellation()}finally{cancelled=true}},{denied})
  r.start(lease);val refresh=async{r.refresh(lease)};runCurrent();assertTrue(entered.isCompleted);entered.await();r.setSharing(lease,false);refresh.join()
  assertTrue(cancelled);assertEquals(listOf("clear-device-usage"),ops)
  val saved=checkNotNull(s.read(lease.ownerId));assertFalse(saved.consent);assertNull(saved.latestAggregate)
  assertEquals(2L,(saved.pending as PendingClear).clear.sequence)
 }
 @Test fun deliberateNewOptInUsesHigherSequenceAndAcknowledgesOnlyMatchingPending()=runTest {
  val s=store();s.update(lease.ownerId){it.copy(consent=true)};s.newPendingReport(lease.ownerId,report)
  val sequences=mutableListOf<Long>()
  val sender=UsageReporter({it==lease},{_,op,body->val seq=if(op=="clear-device-usage")Json.decodeFromString<ClearUsageV1>(body).sequence else Json.decodeFromString<UsageReportV1>(body).sequence;sequences+=seq;reply(seq)})
  val r=UsageRuntime(backgroundScope,s,sender,{it==lease},{true},{_,_->UsageCollection(report,null)},{denied})
  r.start(lease);r.setSharing(lease,false);r.setSharing(lease,true)
  assertEquals(listOf(2L,3L),sequences);assertNull(s.read(lease.ownerId)?.pending);assertTrue(s.read(lease.ownerId)?.consent==true)
 }
 @Test fun lostPayloadRequiresVerifiedCheckpointAndDoesNotRestoreConsent()=runTest {
  val s=store();s.update(lease.ownerId){it.copy(consent=true)};s.newPendingReport(lease.ownerId,report);values.clear()
  var reads=0;val ops=mutableListOf<String>()
  val sender=UsageReporter({it==lease},{_,op,_->ops+=op;"{\"sequence\":9,\"epochId\":\"${report.epochId}\"}"})
  val r=UsageRuntime(backgroundScope,s,sender,{it==lease},{true},{_,_->reads++;UsageCollection(report,null)},{denied})
  r.start(lease);r.refresh(lease)
  assertEquals(listOf("get-device-usage-checkpoint"),ops);assertEquals(0,reads)
  val saved=checkNotNull(s.read(lease.ownerId));assertEquals(10L,saved.nextSequence);assertFalse(saved.consent);assertNull(saved.pending)
 }
 @Test fun failedCheckpointDuringOptInDoesNotRetryOrGuessSequence()=runTest {
  val s=store();s.update(lease.ownerId){it.copy(consent=true)};s.newPendingReport(lease.ownerId,report);values.clear()
  var calls=0
  val sender=UsageReporter({it==lease},{_,_,_->calls++;throw java.io.IOException("offline")})
  val r=UsageRuntime(backgroundScope,s,sender,{it==lease},{true},{_,_->error("must not enumerate")},{denied})
  r.start(lease);r.setSharing(lease,true)
  assertEquals(1,calls)
  assertThrows(UsageCheckpointLost::class.java){s.read(lease.ownerId)}
 }
 @Test fun permissionLostDuringSessionRefreshQueuesNewerDenialAndShowsDenied()=runTest {
  val s=store();s.update(lease.ownerId){it.copy(consent=true)}
  var permission=true;val sent=mutableListOf<UsageReportV1>()
  val sender=UsageReporter({it==lease},{_,_,body->
   val value=Json.decodeFromString<UsageReportV1>(body);sent+=value
   if(value.usagePermission==UsagePermissionState.GRANTED){permission=false;throw UsagePermissionChanged()}
   reply(value.sequence)
  })
  val r=UsageRuntime(backgroundScope,s,sender,{it==lease},{permission},{_,_->UsageCollection(report,null)},{denied})
  r.start(lease);r.refresh(lease)
  assertEquals(listOf(1L,2L),sent.map{it.sequence});assertTrue(sent.last().days.isEmpty())
  assertEquals(UsageRuntimeStatus.PERMISSION_DENIED,r.state.value)
 }
 @Test fun cancelledAttemptKeepsExactPendingWithoutFalseConfirmation()=runTest {
  val s=store();s.update(lease.ownerId){it.copy(consent=true)}
  val sender=UsageReporter({it==lease},{_,_,_->throw CancellationException("cancelled")})
  val r=UsageRuntime(backgroundScope,s,sender,{it==lease},{true},{_,_->UsageCollection(report,null)},{denied})
  r.start(lease);r.refresh(lease)
  assertTrue(s.read(lease.ownerId)?.pending is PendingReport);assertEquals(0L,s.read(lease.ownerId)?.lastConfirmedSequence)
 }
}
