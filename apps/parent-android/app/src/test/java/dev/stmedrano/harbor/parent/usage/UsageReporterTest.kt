package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class UsageReporterTest {
 private fun hash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString(""){"%02x".format(it.toInt() and 255)}
 private val lease=ProfileLease(ProfileRole.CHILD,"22222222-2222-4222-8222-222222222222",1)
 private val report=Json.decodeFromString<UsageReportV1>(checkNotNull(javaClass.classLoader?.getResource("usage-report-v1.json")).readText())
 private val raw=Json.encodeToString(UsageReportV1.serializer(),report)+"  "
 private val pending=PendingReport(report,raw,hash(raw))
 private val reply="{\"confirmed\":true,\"sequence\":${report.sequence},\"receivedAt\":\"2026-10-08T12:00:00Z\"}"
 @Test fun exactRetryBytesAndFixedOperations()=runTest {
  val sent=mutableListOf<Pair<String,String>>()
  val r=UsageReporter({it==lease},{_,op,body->sent+=op to body;reply})
  assertEquals(report.sequence,r.upload(lease,pending).sequence)
  r.upload(lease,pending)
  assertEquals(listOf("report-device-usage" to raw,"report-device-usage" to raw),sent)
 }
 @Test fun parentOrChangedGenerationCannotSend()=runTest {
  var calls=0
  val r=UsageReporter({it==lease},{_,_,_->calls++;reply})
  for(wrong in listOf(lease.copy(role=ProfileRole.PARENT),lease.copy(generation=2))) {
   try{r.upload(wrong,pending);fail("foreign lease sent")}catch(_:IllegalStateException){}
  }
  assertEquals(0,calls)
 }
 @Test fun changedOwnerAfterReplyIsNotConfirmation()=runTest {
  var current=true
  val r=UsageReporter({current},{_,_,_->current=false;reply})
  try{r.upload(lease,pending);fail("stale confirmation exposed")}catch(_:IllegalStateException){}
 }
 @Test fun invalidConfirmationOrMutatedPendingCannotBeAccepted()=runTest {
  for(body in listOf(reply.replace("true","false"),reply.replace("\"sequence\":${report.sequence}","\"sequence\":999"))) {
   val r=UsageReporter({true},{_,_,_->body})
   try{r.upload(lease,pending);fail("invalid confirmation exposed")}catch(_:IllegalStateException){}
  }
  var calls=0
  val r=UsageReporter({true},{_,_,_->calls++;reply})
  try{r.upload(lease,pending.copy(bodyUtf8=raw+" "));fail("changed bytes sent")}catch(_:IllegalStateException){}
  assertEquals(0,calls)
 }
 @Test fun clearAndVerifiedCheckpointUseIndependentOperations()=runTest {
  val clear=ClearUsageV1(1,report.epochId,7)
  val body=Json.encodeToString(ClearUsageV1.serializer(),clear)
  val sent=mutableListOf<Pair<String,String>>()
  val r=UsageReporter({it==lease},{_,op,bytes->
   sent+=op to bytes
   if(op=="clear-device-usage") "{\"confirmed\":true,\"sequence\":7,\"receivedAt\":\"2026-10-08T12:00:00Z\"}"
   else "{\"sequence\":7,\"epochId\":\"${report.epochId}\"}"
  })
  assertEquals(7L,r.clear(lease,PendingClear(clear,body,hash(body))).sequence)
  assertEquals(7L,r.recoverCheckpoint(lease))
  assertEquals(listOf("clear-device-usage" to body,"get-device-usage-checkpoint" to "{\"version\":1}"),sent)
 }
 @Test fun cancellationIsPropagated()=runTest {
  val r=UsageReporter({true},{_,_,_->throw CancellationException("cancelled")})
  try{r.upload(lease,pending);fail("cancelled send accepted")}catch(_:CancellationException){}
 }
}
