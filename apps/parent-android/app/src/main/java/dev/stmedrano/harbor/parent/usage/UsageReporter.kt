package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json

/** Sends persisted bytes only; the adapter captures the validated child session. */
class UsageReporter(private val current:(ProfileLease)->Boolean,private val send:suspend(ProfileLease,String,String)->String) {
 private fun requireLease(lease:ProfileLease) {check(lease.role==ProfileRole.CHILD&&lease.generation>0&&current(lease)){"Child profile changed"}}
 private fun hash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
 private suspend fun request(lease:ProfileLease,operation:String,body:String):String {
  currentCoroutineContext().ensureActive();requireLease(lease)
  val response=send(lease,operation,body)
  currentCoroutineContext().ensureActive();requireLease(lease)
  return response
 }
 private fun confirmation(body:String,sequence:Long):UsageWriteReplyV1 {
  val reply=Json.decodeFromString<UsageWriteReplyV1>(body)
  check(reply.confirmed&&reply.sequence==sequence){"Usage write unconfirmed"}
  java.time.Instant.parse(reply.receivedAt)
  return reply
 }
 suspend fun upload(lease:ProfileLease,pending:PendingReport):UsageWriteReplyV1 {
  check(hash(pending.bodyUtf8)==pending.hash&&Json.decodeFromString<UsageReportV1>(pending.bodyUtf8)==pending.report){"Pending usage bytes changed"}
  return confirmation(request(lease,"report-device-usage",pending.bodyUtf8),pending.report.sequence)
 }
 suspend fun clear(lease:ProfileLease,pending:PendingClear):UsageWriteReplyV1 {
  check(hash(pending.bodyUtf8)==pending.hash&&Json.decodeFromString<ClearUsageV1>(pending.bodyUtf8)==pending.clear){"Pending clear bytes changed"}
  return confirmation(request(lease,"clear-device-usage",pending.bodyUtf8),pending.clear.sequence)
 }
 suspend fun checkpoint(lease:ProfileLease):UsageCheckpointReplyV1=decodeUsageCheckpoint(request(lease,"get-device-usage-checkpoint","{\"version\":1}"))
 suspend fun recoverCheckpoint(lease:ProfileLease):Long=checkpoint(lease).sequence
}
