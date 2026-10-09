package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.auth.AuthCipher
import dev.stmedrano.harbor.parent.auth.AuthValues
import kotlinx.serialization.Serializable

@Serializable sealed interface UsagePending
@Serializable data class PendingReport(val report:UsageReportV1,val bodyUtf8:String,val hash:String):UsagePending
@Serializable data class PendingClear(val clear:ClearUsageV1,val bodyUtf8:String,val hash:String):UsagePending
@Serializable data class UsageStoredState(val bindingId:String,val epochId:String,val nextSequence:Long=1,val consent:Boolean=false,val latestAggregate:UsageReduction?=null,val pending:UsagePending?=null,val lastConfirmedSequence:Long=0,val receivedAt:String?=null)
data class UsageHistoryState(val bindingHash:String,val floor:Long)
interface UsageHistory {fun read():UsageHistoryState?;fun write(value:UsageHistoryState);fun clear()}
class UsageCheckpointLost:IllegalStateException("Usage checkpoint unavailable; verified recovery required")
interface UsageStore {
    fun read(bindingId:String):UsageStoredState?
    fun update(bindingId:String,change:(UsageStoredState)->UsageStoredState):UsageStoredState
    fun clearPayload(bindingId:String)
    fun eraseBinding(bindingId:String)
}
class EncryptedUsageStore(private val values:AuthValues,private val cipher:AuthCipher,private val history:UsageHistory,private val now:()->Long,private val monitor:Any=values,private val eraseKey:()->Unit={}):UsageStore {
    private val secure=dev.stmedrano.harbor.parent.auth.SecureAuthStore(values,cipher)
    private val json=kotlinx.serialization.json.Json {encodeDefaults=true}
    private fun hash(value:String)=java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){"%02x".format(it.toInt() and 255)}
    private fun loaded(bindingId:String):UsageStoredState? {
        val h=history.read()
        if(h!=null&&h.bindingHash!=hash(bindingId))return null
        val raw=try{secure.read("record")}catch(_:dev.stmedrano.harbor.parent.auth.AuthStorageLost){throw UsageCheckpointLost()}
        if(raw==null){if(h!=null)throw UsageCheckpointLost();return null}
        val state=try{json.decodeFromString<UsageStoredState>(raw)}catch(_:Exception){throw UsageCheckpointLost()}
        if(state.bindingId!=bindingId)return null
        if(h==null||h.floor<0||state.nextSequence<=h.floor||state.nextSequence>9_007_199_254_740_991L)throw UsageCheckpointLost()
        return state
    }
    private fun persist(state:UsageStoredState) {
        require(state.nextSequence in 1..9_007_199_254_740_991L)
        val encoded=json.encodeToString(UsageStoredState.serializer(),state)
        require(encoded.toByteArray(Charsets.UTF_8).size<=16*1024*1024){"Usage snapshot storage bound exceeded"}
        history.write(UsageHistoryState(hash(state.bindingId),state.nextSequence-1))
        secure.write("record",encoded)
    }
    private fun pruned(state:UsageStoredState):UsageStoredState {
        val old=state.latestAggregate
        val zone=java.time.ZoneId.of(old?.window?.zoneId?:((state.pending as? PendingReport)?.report?.zoneId?:"UTC"))
        val cutoff=java.time.Instant.ofEpochMilli(now()).atZone(zone).toLocalDate().minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
        val latest=old?.takeIf{it.window.endMs>=cutoff}?.let {r->
            r.copy(days=r.days.filter{java.time.Instant.parse(it.endAt).toEpochMilli()>cutoff},coverage=UsageCoverage(r.coverage.slices.mapNotNull{v->val start=maxOf(v.startMs,cutoff);if(start<v.endMs)v.copy(startMs=start)else null}),window=r.window.copy(startMs=maxOf(r.window.startMs,cutoff)))
        }
        val pending=state.pending.takeUnless{it is PendingReport&&java.time.Instant.parse(it.report.observedAt).toEpochMilli()<cutoff}
        return state.copy(latestAggregate=latest,pending=pending)
    }
    override fun read(bindingId:String):UsageStoredState?=synchronized(monitor) {
        val state=loaded(bindingId)?:return@synchronized null
        val trimmed=pruned(state);if(trimmed!=state)persist(trimmed);trimmed
    }
    override fun update(bindingId:String,change:(UsageStoredState)->UsageStoredState):UsageStoredState=synchronized(monitor) {
        val h=history.read();check(h==null||h.bindingHash==hash(bindingId)){"Foreign usage binding"}
        val state=read(bindingId)?:UsageStoredState(bindingId,java.util.UUID.randomUUID().toString())
        val next=change(state)
        require(next.bindingId==bindingId&&next.nextSequence>=state.nextSequence)
        persist(next);next
    }
    override fun clearPayload(bindingId:String)=synchronized(monitor) {
        val owner=history.read()
        if(owner==null||owner.bindingHash!=hash(bindingId))return@synchronized
        try {update(bindingId){it.copy(latestAggregate=null,pending=it.pending as? PendingClear)};Unit}
        catch(_:UsageCheckpointLost){secure.clear()} // Keep the owned sequence floor; signed recovery remains mandatory.
    }
    override fun eraseBinding(bindingId:String) = synchronized(monitor) {
        val h=history.read()
        if(h?.bindingHash==hash(bindingId)){secure.clear();history.clear();eraseKey()}
    }
    fun newPendingReport(bindingId:String,report:UsageReportV1):PendingReport=synchronized(monitor) {
        val state=read(bindingId)?:throw UsageCheckpointLost();check(state.consent){"Usage sharing not enabled"}
        val numbered=validateUsageReport(report.copy(epochId=state.epochId,sequence=state.nextSequence),now())
        val body=json.encodeToString(UsageReportV1.serializer(),numbered)
        val pending=PendingReport(numbered,body,hash(body))
        update(bindingId){it.copy(nextSequence=numbered.sequence+1,pending=pending)}
        pending
    }
    fun newPendingClear(bindingId:String):PendingClear=synchronized(monitor) {
        val state=read(bindingId)?:throw UsageCheckpointLost()
        val input=ClearUsageV1(1,state.epochId,state.nextSequence)
        val body=json.encodeToString(ClearUsageV1.serializer(),input)
        val pending=PendingClear(input,body,hash(body))
        update(bindingId){it.copy(nextSequence=input.sequence+1,consent=false,latestAggregate=null,pending=pending)}
        pending
    }
    /** Only call with a reply from the verified signed binding; metadata never authorizes recovery. */
    fun restoreCheckpoint(bindingId:String,reply:UsageCheckpointReplyV1):UsageStoredState=synchronized(monitor) {
        decodeUsageCheckpoint(json.encodeToString(UsageCheckpointReplyV1.serializer(),reply))
        val h=history.read();check(h==null||h.bindingHash==hash(bindingId)){"Foreign usage binding"}
        val floor=maxOf(reply.sequence,h?.floor?:0L);require(floor<9_007_199_254_740_991L)
        val state=UsageStoredState(bindingId,reply.epochId?:java.util.UUID.randomUUID().toString(),nextSequence=floor+1)
        persist(state);state
    }
    companion object {
        /** Separate usage namespace; never opens parent or primary child Auth storage. */
        fun open(context:android.content.Context,name:String="harbor-child-usage",now:()->Long=System::currentTimeMillis):EncryptedUsageStore {

            val prefs=context.applicationContext.getSharedPreferences(name,android.content.Context.MODE_PRIVATE)
            val marker=context.applicationContext.getSharedPreferences("$name-history",android.content.Context.MODE_PRIVATE)
            val values=object:AuthValues {
                override fun read(key:String)=prefs.getString(key,null)
                override fun write(key:String,value:String?){check(prefs.edit().putString(key,value).commit())}
                override fun clear(){check(prefs.edit().clear().commit())}
            }
            val history=object:UsageHistory {
                override fun read():UsageHistoryState?=marker.getString("binding",null)?.let{UsageHistoryState(it,marker.getLong("floor",-1))}
                override fun write(value:UsageHistoryState){check(marker.edit().putString("binding",value.bindingHash).putLong("floor",value.floor).commit())}
                override fun clear(){check(marker.edit().clear().commit())}
            }
            val alias="$name-aes-v1"
            return EncryptedUsageStore(values,dev.stmedrano.harbor.parent.auth.KeystoreCipher(alias),history,now,prefs) {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry(alias)}
            }

        }
    }}
