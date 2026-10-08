package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.profile.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UsageCollection(val report:UsageReportV1,val aggregate:UsageReduction?)
enum class UsageRuntimeStatus { DISABLED, READY, PERMISSION_DENIED, OFFLINE, CHECKPOINT_REQUIRED }
class UsagePermissionChanged:IllegalStateException("Usage permission changed")
class UsageRuntime(private val scope:CoroutineScope,private val store:EncryptedUsageStore,private val reporter:UsageReporter,
 private val current:(ProfileLease)->Boolean,private val permission:()->Boolean,
 private val collect:suspend(ProfileLease,UsageStoredState)->UsageCollection,private val denied:()->UsageReportV1) {
 private val control=Mutex()
 @Volatile private var active:ProfileLease?=null
 private var running:Deferred<Unit>?=null
 private val mutableState=MutableStateFlow(UsageRuntimeStatus.DISABLED)
 val state=mutableState.asStateFlow()
 private fun valid(lease:ProfileLease)=active==lease&&lease.role==ProfileRole.CHILD&&current(lease)
 private fun requireCurrent(lease:ProfileLease){check(valid(lease)){"Usage profile changed"}}
 private suspend fun checked(lease:ProfileLease){currentCoroutineContext().ensureActive();requireCurrent(lease)}
 private suspend fun cancelAttempt(){val old=running;running=null;old?.cancelAndJoin()}
 suspend fun start(lease:ProfileLease)=control.withLock {
  if(active==lease)return@withLock
  cancelAttempt();active=lease.takeIf{it.role==ProfileRole.CHILD};mutableState.value=UsageRuntimeStatus.DISABLED
 }
 suspend fun stop()=control.withLock {
  val old=active;active=null;cancelAttempt()
  if(old!=null&&store.read(old.ownerId)!=null)store.clearPayload(old.ownerId)
  mutableState.value=UsageRuntimeStatus.DISABLED
 }
 private suspend fun recovered(lease:ProfileLease,create:Boolean):UsageStoredState? {
  checked(lease)
  return try {store.read(lease.ownerId)?:if(create)store.update(lease.ownerId){it}else null}
  catch(_:UsageCheckpointLost) {
   val reply=reporter.checkpoint(lease);checked(lease)
   store.restoreCheckpoint(lease.ownerId,reply)
  }
 }
 private fun acknowledge(lease:ProfileLease,pending:UsagePending,reply:UsageWriteReplyV1) {
  requireCurrent(lease)
  store.update(lease.ownerId){s->if(s.pending==pending)s.copy(pending=null,lastConfirmedSequence=reply.sequence,receivedAt=reply.receivedAt)else s}
 }
 private suspend fun upload(lease:ProfileLease,pending:PendingReport) {
  checked(lease);check(store.read(lease.ownerId)?.consent==true)
  try {acknowledge(lease,pending,reporter.upload(lease,pending))}
  catch(_:UsagePermissionChanged) {
   checked(lease)
   val replacement=store.newPendingReport(lease.ownerId,denied())
   store.update(lease.ownerId){it.copy(latestAggregate=null)}
   acknowledge(lease,replacement,reporter.upload(lease,replacement))
  }
 }
 private suspend fun attempt(lease:ProfileLease,action:suspend()->Unit) {
  try {withTimeout(15000){checked(lease);action()}}
  catch(_:TimeoutCancellationException){if(valid(lease))mutableState.value=UsageRuntimeStatus.OFFLINE}
  catch(cancelled:CancellationException){throw cancelled}
  catch(_:UsageCheckpointLost){if(valid(lease))mutableState.value=UsageRuntimeStatus.CHECKPOINT_REQUIRED}
  catch(_:Exception){if(valid(lease))mutableState.value=UsageRuntimeStatus.OFFLINE}
 }
 suspend fun refresh(lease:ProfileLease) {
  val work=control.withLock {
   if(!valid(lease))return
   cancelAttempt()
   scope.async {
    attempt(lease) {
     val saved=recovered(lease,false)?:return@attempt
     if(!saved.consent) {
      val clear=saved.pending as? PendingClear
      if(clear!=null){checked(lease);acknowledge(lease,clear,reporter.clear(lease,clear))}
      mutableState.value=UsageRuntimeStatus.DISABLED;return@attempt
     }
     val collected=if(permission())collect(lease,saved)else UsageCollection(denied(),null)
     checked(lease);check(store.read(lease.ownerId)?.consent==true)
     val allowed=permission()
     val report=if(allowed)collected.report else denied()
     store.update(lease.ownerId){it.copy(latestAggregate=if(allowed)collected.aggregate else null)}
     val pending=store.newPendingReport(lease.ownerId,report)
     checked(lease);upload(lease,pending);checked(lease)
     mutableState.value=if(permission())UsageRuntimeStatus.READY else UsageRuntimeStatus.PERMISSION_DENIED
    }
   }.also{running=it}
  }
  try {work.await()}catch(cancelled:CancellationException){currentCoroutineContext().ensureActive()}
 }
 suspend fun setSharing(lease:ProfileLease,enabled:Boolean) {
  var consentSaved=false
  control.withLock {
   if(!valid(lease))return
   cancelAttempt()
   attempt(lease) {
    recovered(lease,true);checked(lease)
    if(enabled){store.update(lease.ownerId){it.copy(consent=true)};consentSaved=true}
    else {
     val clear=store.newPendingClear(lease.ownerId)
     mutableState.value=UsageRuntimeStatus.DISABLED
     acknowledge(lease,clear,reporter.clear(lease,clear))
    }
   }
  }
  if(enabled&&consentSaved&&valid(lease))refresh(lease)
 }
}
