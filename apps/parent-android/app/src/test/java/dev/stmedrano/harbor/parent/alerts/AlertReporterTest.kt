package dev.stmedrano.harbor.parent.alerts

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

class AlertReporterTest {
    private val lease = ProfileLease(ProfileRole.CHILD, "22222222-2222-4222-8222-222222222222", 1)
    private val nowMs = java.time.Instant.parse("2026-10-10T18:00:00Z").toEpochMilli()
    private val epoch = "9b2f3c1e-6a47-4d58-8e0b-3f1a2c4d5e6f"
    private val watching = AlertAccess(notificationAccess = true, smsPermission = true)

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
    private val store = EncryptedAlertStore(values, cipher, history)

    private fun id(i: Int) = "00000000-0000-4000-8000-%012d".format(i)
    private fun alert(i: Int, minutesAgo: Long, category: AlertCategory = AlertCategory.BULLYING) =
        MessageAlert(id(i), "WhatsApp", category, 1, alertInstantString(nowMs - minutesAgo * 60_000), false)
    private fun ok(seq: Long) = "{\"confirmed\":true,\"sequence\":$seq,\"receivedAt\":\"2026-10-10T18:00:01Z\"}"

    private class Sent(val operation: String, val body: String)
    private fun reporter(current: () -> Boolean = { true }, handler: (Sent) -> String): Pair<AlertReporter, MutableList<Sent>> {
        val sent = mutableListOf<Sent>()
        val reporter = AlertReporter(store, { current() && it == lease }, { _, op, body -> Sent(op, body).also { sent += it }.let(handler) }, { nowMs })
        return reporter to sent
    }
    private fun report(sent: Sent) = Json.decodeFromString<AlertReportV1>(sent.body)

    @Test fun firstFlushReportsAccessEvenWithoutAlerts() = runTest {
        val (reporter, sent) = reporter { ok(1) }
        reporter.flush(lease, watching)
        assertEquals(1, sent.size)
        assertEquals("report-message-alert", sent[0].operation)
        assertEquals(emptyList<MessageAlert>(), report(sent[0]).alerts)
        assertTrue(report(sent[0]).notificationAccess && report(sent[0]).smsPermission)
    }

    @Test fun alertsAreOrderedDedupedByIdAndSentOnce() = runTest {
        val (reporter, sent) = reporter { ok(1) }
        // Same id recorded twice (a replay): the later record wins and only one alert is sent.
        listOf(alert(1, 5), alert(2, 15), alert(1, 3, AlertCategory.SELF_HARM), alert(3, 10)).forEach { reporter.record(lease, it) }
        reporter.flush(lease, watching)
        reporter.flush(lease, watching)
        assertEquals(1, sent.size)
        val alerts = report(sent[0]).alerts
        assertEquals(listOf(id(2), id(3), id(1)), alerts.map { it.id })
        assertEquals("the later record for the same id wins", AlertCategory.SELF_HARM, alerts.last().category)
        assertEquals(emptyList<MessageAlert>(), store.read(lease.ownerId)?.queue)
    }

    @Test fun largeQueuesGoOutInFiftyAlertBatchesWithRisingSequences() = runTest {
        var seq = 0L
        val (reporter, sent) = reporter { seq++; ok(seq) }
        (1..120).forEach { reporter.record(lease, alert(it, (130 - it).toLong())) }
        reporter.flush(lease, watching)
        assertEquals(listOf(50, 50, 20), sent.map { report(it).alerts.size })
        assertEquals(listOf(1L, 2L, 3L), sent.map { report(it).sequence })
        assertTrue(report(sent[0]).alerts.first().at <= report(sent[1]).alerts.first().at)
    }

    @Test fun queueIsCappedAtTwoHundredKeepingNewest() = runTest {
        val (reporter, _) = reporter { error("offline") }
        (1..220).forEach { reporter.record(lease, alert(it, (260 - it).toLong())) }
        val queue = checkNotNull(store.read(lease.ownerId)).queue
        assertEquals(200, queue.size)
        assertEquals(id(220), queue.last().id)   // newest kept
        assertEquals(id(21), queue.first().id)   // oldest 20 dropped
    }

    @Test fun offlineKeepsExactBytesAndRetryResendsThemIdempotently() = runTest {
        var online = false
        val (reporter, sent) = reporter { if (online) ok(1) else throw java.io.IOException("offline") }
        reporter.record(lease, alert(1, 5))
        assertThrows(java.io.IOException::class.java) { kotlinx.coroutines.runBlocking { reporter.flush(lease, watching) } }
        val firstBytes = sent[0].body
        assertEquals(firstBytes, store.read(lease.ownerId)?.pending?.bodyUtf8)
        online = true
        reporter.flush(lease, watching)
        assertEquals("the retry re-sends the exact same bytes so the server dedupes by id", listOf(firstBytes, firstBytes), sent.map { it.body })
        assertNull(store.read(lease.ownerId)?.pending)
    }

    @Test fun accessIsResentOnlyWhenItChanges() = runTest {
        var seq = 0L
        val (reporter, sent) = reporter { seq++; ok(seq) }
        reporter.flush(lease, watching)
        reporter.flush(lease, watching)
        reporter.flush(lease, watching.copy(notificationAccess = false))
        assertEquals(listOf(true, false), sent.map { report(it).notificationAccess })
        assertEquals(listOf(1L, 2L), sent.map { report(it).sequence })
    }

    @Test fun staleSequenceIsRepairedFromTheCheckpointWithoutLosingAlerts() = runTest {
        val serverEpoch = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        var rejectedOnce = false
        val (reporter, sent) = reporter {
            when (it.operation) {
                "get-message-alert-checkpoint" -> "{\"sequence\":5,\"epochId\":\"$serverEpoch\"}"
                else -> if (!rejectedOnce) { rejectedOnce = true; throw UsageRequestRejected(409, "STALE_VERSION") } else ok(report(it).sequence)
            }
        }
        reporter.record(lease, alert(1, 5))
        reporter.flush(lease, watching)
        val reports = sent.filter { it.operation == "report-message-alert" }.map(::report)
        assertEquals(listOf(1L, 6L), reports.map { it.sequence })
        assertEquals(serverEpoch, reports[1].epochId)
        assertEquals(reports[0].alerts.map { it.id }, reports[1].alerts.map { it.id })
    }

    @Test fun otherRejectionsAreNotRepairedAndKeepTheReportPending() = runTest {
        val (reporter, sent) = reporter { throw UsageRequestRejected(400, "VALIDATION_FAILED") }
        reporter.record(lease, alert(1, 5))
        assertThrows(UsageRequestRejected::class.java) { kotlinx.coroutines.runBlocking { reporter.flush(lease, watching) } }
        assertEquals(1, sent.size)
        assertNotNull(store.read(lease.ownerId)?.pending)
    }

    @Test fun aChangedProfileNeverSends() = runTest {
        var current = true
        val (reporter, sent) = reporter({ current }) { ok(1) }
        reporter.record(lease, alert(1, 5))
        current = false
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { reporter.flush(lease, watching) } }
        assertEquals(0, sent.size)
    }

    @Test fun alertsOlderThanRetentionAreDroppedAndFutureAlertsWait() = runTest {
        val (reporter, sent) = reporter { ok(1) }
        val old = alert(1, 0).copy(at = alertInstantString(nowMs - ALERT_RETENTION_MS - 60_000))
        val future = alert(2, 0).copy(at = alertInstantString(nowMs + 120_000))
        reporter.record(lease, old); reporter.record(lease, future); reporter.record(lease, alert(3, 5))
        reporter.flush(lease, watching)
        assertEquals(listOf(id(3)), report(sent[0]).alerts.map { it.id })
        assertEquals(listOf(future.id), store.read(lease.ownerId)?.queue?.map { it.id })
    }
}
