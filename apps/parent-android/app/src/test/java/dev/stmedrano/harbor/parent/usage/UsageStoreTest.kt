package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.auth.AuthCipher
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class UsageStoreTest {
    private class Values:AuthValues {
        val data=mutableMapOf<String,String>()
        var failWrites=false
        override fun read(key:String)=data[key]
        override fun write(key:String,value:String?){check(!failWrites);if(value==null)data.remove(key)else data[key]=value}
        override fun clear(){data.clear()}
    }
    private class Cipher:AuthCipher {
        var lost=false
        override fun encrypt(slot:String,value:ByteArray)=value.reversedArray()
        override fun decrypt(slot:String,value:ByteArray):ByteArray {check(!lost);return value.reversedArray()}
    }
    private class History:UsageHistory {
        var state:UsageHistoryState?=null
        override fun read()=state
        override fun write(value:UsageHistoryState){state=value}
        override fun clear(){state=null}
    }
    private val now=Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private val values=Values();private val cipher=Cipher();private val history=History()
    private fun store(clock:Long=now)=EncryptedUsageStore(values,cipher,history,{clock})
    private fun report()=decodeUsageReport(javaClass.getResource("/usage-report-v1.json")!!.readText(),now)
    private fun enabled(s:EncryptedUsageStore=store()):EncryptedUsageStore {s.update("binding-a"){it.copy(consent=true)};return s}
    @Test fun restartKeepsSequenceAndExactBytes() {
        val p=enabled().newPendingReport("binding-a",report())
        val reopened=store().read("binding-a")!!
        assertEquals(p,reopened.pending);assertEquals(p.report.sequence+1,reopened.nextSequence)
        assertFalse(values.data.values.joinToString().contains("example.test"))
        assertEquals(report().days,p.report.days)
    }
    @Test fun newReportReplacesOnePending() {
        val s=enabled();val first=s.newPendingReport("binding-a",report());val next=s.newPendingReport("binding-a",report())
        assertEquals(first.report.sequence+1,next.report.sequence);assertEquals(next,s.read("binding-a")!!.pending)
    }
    @Test fun clearPreservesHigherCheckpointButErasesMetrics() {
        val s=enabled();val p=s.newPendingReport("binding-a",report());val clear=s.newPendingClear("binding-a")
        val state=s.read("binding-a")!!
        assertFalse(state.consent);assertNull(state.latestAggregate);assertEquals(clear,state.pending)
        assertTrue(clear.clear.sequence>p.report.sequence);assertFalse(clear.bodyUtf8.contains("example.test"))
        s.clearPayload("binding-a");assertEquals(clear,s.read("binding-a")!!.pending)
    }
    @Test fun foreignBindingCannotReadOrOverwrite() {
        val s=enabled();s.newPendingReport("binding-a",report());assertNull(s.read("foreign"))
        assertThrows(IllegalStateException::class.java){s.update("foreign"){it.copy(consent=true)}}
        assertNotNull(s.read("binding-a"))
    }
    @Test fun keyLossDoesNotResetToSequenceOne() {
        val s=enabled();s.newPendingReport("binding-a",report());cipher.lost=true
        assertThrows(UsageCheckpointLost::class.java){s.read("binding-a")}
        assertThrows(UsageCheckpointLost::class.java){store().newPendingReport("binding-a",report())}
        cipher.lost=false
        val recovered=store().restoreCheckpoint("binding-a",UsageCheckpointReplyV1(0,null))
        assertFalse(recovered.consent);assertTrue(recovered.nextSequence>1)
    }
    @Test fun sevenDayPruningDoesNotInventFreshCollection() {
        val s=enabled();s.newPendingReport("binding-a",report())
        val later=store(now+8*86400000).read("binding-a")!!
        assertNull(later.pending);assertNull(later.latestAggregate);assertTrue(later.nextSequence>1)
    }
    @Test fun newConsentAdvancesClearSequenceAndNoTokensEnterState() {
        val s=enabled();s.newPendingReport("binding-a",report());val clear=s.newPendingClear("binding-a")
        enabled(s);val fresh=s.newPendingReport("binding-a",report())
        assertTrue(fresh.report.sequence>clear.clear.sequence)
        val persisted=cipher.decrypt("record",java.util.Base64.getDecoder().decode(values.data.getValue("record"))).toString(Charsets.UTF_8)
        assertFalse(persisted.contains("accessToken"));assertFalse(persisted.contains("refreshToken"));assertFalse(persisted.contains("className"))
    }
    @Test fun interruptedWriteRetainsFloorAndNeverReusesSequence() {
        val s=enabled();val p=s.newPendingReport("binding-a",report());values.failWrites=true
        assertThrows(IllegalStateException::class.java){s.newPendingReport("binding-a",report())}
        values.failWrites=false
        assertThrows(UsageCheckpointLost::class.java){store().read("binding-a")}
        val recovered=store().restoreCheckpoint("binding-a",UsageCheckpointReplyV1(0,null))
        assertTrue(recovered.nextSequence>p.report.sequence+1)
    }
    @Test fun separateStoreObjectsAllocateSerialSequences() {
        val s=enabled();val other=store()
        val threads=List(2){index->Thread{repeat(10){(if(index==0)s else other).newPendingReport("binding-a",report())}}}
        threads.forEach{it.start()};threads.forEach{it.join()}
        assertEquals(21L,s.read("binding-a")!!.nextSequence)
        assertEquals(20L,(s.read("binding-a")!!.pending as PendingReport).report.sequence)
    }}
