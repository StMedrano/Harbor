package dev.stmedrano.harbor.parent.location

import dev.stmedrano.harbor.parent.auth.AuthCipher
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.child.UsageRequestRejected
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import dev.stmedrano.harbor.parent.usage.UsageHistory
import dev.stmedrano.harbor.parent.usage.UsageHistoryState
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class LocationEngineTest {
    private val lease = ProfileLease(ProfileRole.CHILD, "22222222-2222-4222-8222-222222222222", 1)
    private val nowMs = java.time.Instant.parse("2026-10-10T18:00:00Z").toEpochMilli()
    private val epoch = "9b2f3c1e-6a47-4d58-8e0b-3f1a2c4d5e6f"
    private class Values : AuthValues {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun clear() { data.clear() }
    }
    private val values = Values()
    private val history = object : UsageHistory {
        var value: UsageHistoryState? = null
        override fun read() = value
        override fun write(value: UsageHistoryState) { this.value = value }
        override fun clear() { value = null }
    }
    private val cipher = object : AuthCipher {
        override fun encrypt(slot: String, value: ByteArray) = value.reversedArray()
        override fun decrypt(slot: String, value: ByteArray) = value.reversedArray()
    }
    private val store = EncryptedLocationStore(values, cipher, history)
    private fun fix(minutesAgo: Long, lat: Double = 41.878114) = LocationFix(locationInstantString(nowMs - minutesAgo * 60_000), lat, -87.629798, 12, LocationProvider.FUSED)
    private fun ok(seq: Long) = "{\"confirmed\":true,\"sequence\":$seq,\"receivedAt\":\"2026-10-10T18:00:01Z\"}"
    private class Sent(val operation: String, val body: String)
    private fun engine(current: () -> Boolean = { true }, handler: (Sent) -> String): Pair<LocationEngine, MutableList<Sent>> {
        val sent = mutableListOf<Sent>()
        val engine = LocationEngine(store, { current() && it == lease }, { _, op, body -> Sent(op, body).also { sent += it }.let(handler) }, { nowMs })
        return engine to sent
    }
    private fun report(sent: Sent) = Json.decodeFromString<LocationReportV1>(sent.body)

    @Test fun firstFlushReportsStatusEvenWithoutFixes() = runTest {
        val (engine, sent) = engine { ok(1) }
        engine.flush(lease, LocationStatus.SHARING)
        assertEquals(1, sent.size)
        assertEquals("report-device-location", sent[0].operation)
        assertEquals(emptyList<LocationFix>(), report(sent[0]).fixes)
        assertEquals(LocationStatus.SHARING, report(sent[0]).status)
        assertEquals(LocationStatus.SHARING, store.read(lease.ownerId)?.reportedStatus)
    }
    @Test fun fixesAreOrderedDedupedAndSentOnce() = runTest {
        val (engine, sent) = engine { ok(1) }
        listOf(fix(5), fix(15), fix(10), fix(10, lat = 41.9)).forEach { engine.record(lease, it) }
        engine.flush(lease, LocationStatus.SHARING)
        engine.flush(lease, LocationStatus.SHARING)
        assertEquals(1, sent.size)
        val fixes = report(sent[0]).fixes
        assertEquals(listOf(fix(15).at, fix(10).at, fix(5).at), fixes.map { it.at })
        assertEquals("the later duplicate for the same second wins", 41.9, fixes[1].latitude, 0.0)
        assertEquals(emptyList<LocationFix>(), store.read(lease.ownerId)?.queue)
    }
    @Test fun largeQueuesGoOutInHundredFixBatchesWithRisingSequences() = runTest {
        var seq = 0L
        val (engine, sent) = engine { seq++; ok(seq) }
        (1L..150L).forEach { engine.record(lease, fix(it)) }
        engine.flush(lease, LocationStatus.SHARING)
        assertEquals(listOf(100, 50), sent.map { report(it).fixes.size })
        assertEquals(listOf(1L, 2L), sent.map { report(it).sequence })
        assertTrue(report(sent[0]).fixes.first().at < report(sent[1]).fixes.first().at)
    }
    @Test fun queueIsCappedAtFiveHundredKeepingNewest() = runTest {
        val (engine, _) = engine { error("offline") }
        (1L..520L).forEach { engine.record(lease, fix(it)) }
        val queue = checkNotNull(store.read(lease.ownerId)).queue
        assertEquals(500, queue.size)
        assertEquals(fix(1).at, queue.last().at)
        assertEquals(fix(500).at, queue.first().at)
    }
    @Test fun offlineKeepsExactBytesAndRetryResendsThem() = runTest {
        var online = false
        val (engine, sent) = engine { if (online) ok(1) else throw java.io.IOException("offline") }
        engine.record(lease, fix(5))
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { engine.flush(lease, LocationStatus.SHARING) } }
        val firstBytes = sent[0].body
        assertEquals(firstBytes, store.read(lease.ownerId)?.pending?.bodyUtf8)
        online = true
        engine.flush(lease, LocationStatus.SHARING)
        assertEquals(listOf(firstBytes, firstBytes), sent.map { it.body })
        assertNull(store.read(lease.ownerId)?.pending)
        assertEquals(1L, store.read(lease.ownerId)?.lastConfirmedSequence)
    }
    @Test fun statusIsResentOnlyWhenItChanges() = runTest {
        var seq = 0L
        val (engine, sent) = engine { seq++; ok(seq) }
        engine.flush(lease, LocationStatus.SHARING)
        engine.flush(lease, LocationStatus.SHARING)
        engine.flush(lease, LocationStatus.PERMISSION_LOST)
        assertEquals(listOf(LocationStatus.SHARING, LocationStatus.PERMISSION_LOST), sent.map { report(it).status })
        assertEquals(listOf(1L, 2L), sent.map { report(it).sequence })
    }
    @Test fun staleSequenceIsRepairedFromTheCheckpointWithoutLosingFixes() = runTest {
        val serverEpoch = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        var rejectedOnce = false
        val (engine, sent) = engine {
            when (it.operation) {
                "get-device-location-checkpoint" -> "{\"sequence\":5,\"epochId\":\"$serverEpoch\"}"
                else -> if (!rejectedOnce) { rejectedOnce = true; throw UsageRequestRejected(409, "STALE_VERSION") } else ok(report(it).sequence)
            }
        }
        engine.record(lease, fix(5))
        engine.flush(lease, LocationStatus.SHARING)
        val reports = sent.filter { it.operation == "report-device-location" }.map(::report)
        assertEquals(listOf(1L, 6L), reports.map { it.sequence })
        assertEquals(serverEpoch, reports[1].epochId)
        assertEquals(reports[0].fixes, reports[1].fixes)
    }
    @Test fun otherRejectionsAreNotRepairedAndKeepTheReportPending() = runTest {
        val (engine, sent) = engine { throw UsageRequestRejected(400, "VALIDATION_FAILED") }
        engine.record(lease, fix(5))
        assertThrows(UsageRequestRejected::class.java) { kotlinx.coroutines.runBlocking { engine.flush(lease, LocationStatus.SHARING) } }
        assertEquals(1, sent.size)
        assertNotNull(store.read(lease.ownerId)?.pending)
    }
    @Test fun aChangedProfileNeverSends() = runTest {
        var current = true
        val (engine, sent) = engine({ current }) { ok(1) }
        engine.record(lease, fix(5))
        current = false
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { engine.flush(lease, LocationStatus.SHARING) } }
        assertEquals(0, sent.size)
    }
    @Test fun fixesOlderThanRetentionAreDroppedAndFutureFixesWait() = runTest {
        val (engine, sent) = engine { ok(1) }
        val old = fix(0).copy(at = locationInstantString(nowMs - LOCATION_RETENTION_MS - 60_000))
        val future = fix(0).copy(at = locationInstantString(nowMs + 120_000))
        engine.record(lease, old); engine.record(lease, future); engine.record(lease, fix(5))
        engine.flush(lease, LocationStatus.SHARING)
        assertEquals(listOf(fix(5).at), report(sent[0]).fixes.map { it.at })
        assertEquals(listOf(future.at), store.read(lease.ownerId)?.queue?.map { it.at })
    }
    @Test fun lostLocalStorageIsRestoredFromTheVerifiedCheckpoint() = runTest {
        var seq = 0L
        val (engine, sent) = engine {
            if (it.operation == "get-device-location-checkpoint") "{\"sequence\":1,\"epochId\":\"$epoch\"}" else { seq++; ok(seq) }
        }
        engine.flush(lease, LocationStatus.SHARING)
        values.data.clear() // history marker survives, encrypted record does not
        engine.record(lease, fix(5))
        engine.flush(lease, LocationStatus.SHARING)
        val reports = sent.filter { it.operation == "report-device-location" }.map(::report)
        assertEquals(2L, reports.last().sequence)
        assertTrue(sent.any { it.operation == "get-device-location-checkpoint" })
    }
    @Test fun eraseBindingRemovesEverythingAndForeignBindingsAreRefused() = runTest {
        val (engine, _) = engine { ok(1) }
        engine.record(lease, fix(5))
        store.eraseBinding(lease.ownerId)
        assertNull(store.read(lease.ownerId))
        assertTrue(values.data.isEmpty() && history.value == null)
    }
}
