package dev.stmedrano.harbor.parent.location

import dev.stmedrano.harbor.parent.auth.AuthCipher
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import dev.stmedrano.harbor.parent.usage.UsageHistory
import dev.stmedrano.harbor.parent.usage.UsageHistoryState
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChildLocationHostTest {
    private val lease = ProfileLease(ProfileRole.CHILD, "22222222-2222-4222-8222-222222222222", 1)
    private val t0 = java.time.Instant.parse("2026-10-10T18:00:00Z").toEpochMilli()
    private var nowMs = t0
    private class Values : AuthValues {
        val data = mutableMapOf<String, String>()
        override fun read(key: String) = data[key]
        override fun write(key: String, value: String?) { if (value == null) data.remove(key) else data[key] = value }
        override fun clear() { data.clear() }
    }
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
    private val store = EncryptedLocationStore(Values(), cipher, history)
    private var access = LocationAccess(fineGranted = true, backgroundGranted = true, servicesOn = true)
    private var online = true
    private var currentLease = true
    private val bodies = mutableListOf<String>()
    private var sequence = 0L
    private var callback: ((LocationFix) -> Unit)? = null
    private var updatesStarted = 0
    private var updatesStopped = 0
    private val serviceCalls = mutableListOf<Boolean>()
    private var cached: LocationFix? = null
    private fun fix(secondsFromStart: Long, latitude: Double = 41.878114) = LocationFix(locationInstantString(t0 + secondsFromStart * 1000 - 60_000), latitude, -87.629798, 15, LocationProvider.GPS)
    private val platform = LocationPlatform(
        access = { access },
        startUpdates = { callback = it; updatesStarted++; true },
        stopUpdates = { callback = null; updatesStopped++ },
        lastKnown = { cached },
        setService = { serviceCalls += it; true },
    )
    private fun host(scope: TestScope): ChildLocationHost {
        val engine = LocationEngine(store, { currentLease && it == lease }, { _, _, body ->
            if (!online) throw java.io.IOException("offline")
            bodies += body; sequence++
            "{\"confirmed\":true,\"sequence\":$sequence,\"receivedAt\":\"2026-10-10T18:00:01Z\"}"
        }, { nowMs })
        return ChildLocationHost(scope, engine, platform, { currentLease && it == lease }, { nowMs })
    }

    @Test fun sharingStartsUpdatesServiceAndReportsStatus() = runTest {
        val host = host(this)
        cached = fix(0)
        host.start(lease); host.supervise(lease)
        assertEquals(1, updatesStarted)
        assertEquals(listOf(true), serviceCalls)
        assertEquals(LocationStatus.SHARING, host.state.value.status)
        assertEquals(1, bodies.size)
        assertEquals("the cached place is reported at once", 1, Json.decodeFromString<LocationReportV1>(bodies[0]).fixes.size)
        host.supervise(lease)
        assertEquals("already listening", 1, updatesStarted)
        assertEquals("service is only started once", listOf(true), serviceCalls)
    }

    @Test fun lostPermissionStopsUpdatesAndReportsWhyOnce() = runTest {
        val host = host(this)
        host.start(lease); host.supervise(lease)
        access = LocationAccess(fineGranted = false, backgroundGranted = false, servicesOn = true)
        host.supervise(lease)
        assertEquals(LocationStatus.PERMISSION_LOST, host.state.value.status)
        assertEquals(listOf(true, false), serviceCalls)
        assertNull(callback)
        assertEquals(LocationStatus.PERMISSION_LOST, Json.decodeFromString<LocationReportV1>(bodies.last()).status)
        val count = bodies.size
        host.supervise(lease)
        assertEquals("an unchanged status is not resent", count, bodies.size)
    }

    @Test fun missingBackgroundStillRunsAndReportsIt() = runTest {
        access = LocationAccess(fineGranted = true, backgroundGranted = false, servicesOn = true)
        val host = host(this)
        host.start(lease); host.supervise(lease)
        assertEquals(LocationStatus.BACKGROUND_MISSING, host.state.value.status)
        assertEquals(1, updatesStarted)
    }

    @Test fun stationaryFixesAreThinnedToFifteenMinutes() = runTest {
        val host = host(this)
        host.start(lease); host.supervise(lease)
        val first = bodies.size
        callback!!(fix(0)); advanceUntilIdle()
        callback!!(fix(10)); advanceUntilIdle()
        callback!!(fix(70)); advanceUntilIdle()
        assertEquals("a still phone must not upload new locations every minute", first, bodies.size)
        nowMs += 15 * 60_000L
        callback!!(fix(910)); advanceUntilIdle()
        assertEquals(first + 1, bodies.size)
        val sent = Json.decodeFromString<LocationReportV1>(bodies.last()).fixes
        assertEquals(listOf(fix(0).at, fix(910).at), sent.map { it.at })
    }

    @Test fun movingFixesAreThinnedToFiveMinutes() = runTest {
        val host = host(this)
        host.start(lease); host.supervise(lease)
        val first = bodies.size
        callback!!(fix(0)); advanceUntilIdle()
        callback!!(fix(240, latitude = 41.880114)); advanceUntilIdle()
        assertEquals(first, bodies.size)
        nowMs += 310_000L
        callback!!(fix(310, latitude = 41.880114)); advanceUntilIdle()
        assertEquals(first + 1, bodies.size)
        val sent = Json.decodeFromString<LocationReportV1>(bodies.last()).fixes
        assertEquals(listOf(fix(0).at, fix(310).at), sent.map { it.at })
    }

    @Test fun offlineFlagsFailureAndKeepsFixesForLater() = runTest {
        val host = host(this)
        online = false
        host.start(lease); host.supervise(lease)
        assertTrue(host.state.value.uploadFailing)
        online = true
        host.supervise(lease)
        assertFalse(host.state.value.uploadFailing)
        assertNotNull(host.state.value.lastUploadMs)
    }

    @Test fun stoppedOrReplacedProfileIgnoresFixesAndCleansUp() = runTest {
        val host = host(this)
        host.start(lease); host.supervise(lease)
        val live = callback!!
        host.stop()
        assertNull(callback)
        assertEquals(false, serviceCalls.last())
        val before = bodies.size
        live(fix(0)); advanceUntilIdle()
        nowMs += 120_000
        live(fix(130)); advanceUntilIdle()
        assertEquals(before, bodies.size)
        assertNull(host.state.value.status)
    }

    @Test fun otherChildProfileCannotSupervise() = runTest {
        val host = host(this)
        host.start(lease)
        host.supervise(ProfileLease(ProfileRole.CHILD, "33333333-3333-4333-8333-333333333333", 1))
        assertEquals(0, bodies.size)
        assertEquals(0, updatesStarted)
    }
}
