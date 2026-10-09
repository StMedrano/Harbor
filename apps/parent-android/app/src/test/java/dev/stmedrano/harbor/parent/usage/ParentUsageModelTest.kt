package dev.stmedrano.harbor.parent.usage

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ParentUsageModelTest {
    private val parentA = ParentIdentity("parent-a", "session-a")
    private val parentB = ParentIdentity("parent-b", "session-b")
    private val device = "33333333-3333-4333-8333-333333333333"
    private val now = java.time.Instant.parse("2026-10-08T12:20:00Z").toEpochMilli()
    private val report = Json.decodeFromString<UsageReportV1>(checkNotNull(javaClass.classLoader?.getResource("usage-report-v1.json")).readText())
    private fun reply(sequence: Long = 1) = UsageReadReplyV1(UsageReadState.AVAILABLE, report.copy(sequence = sequence), "2026-10-08T12:10:00Z")
    private var identity: ParentIdentity? = parentA

    private fun model(api: ParentUsageApi) = ParentUsageModel(api, { identity }, { now })

    @Test fun readsAreScopedToTheParentAndDevice() = runTest {
        val m = model(object : ParentUsageApi { override suspend fun read(deviceId: String) = reply() })
        m.load(parentA, device)
        val scoped = checkNotNull(m.state.value)
        assertEquals(ParentUsageScope(parentA, device), scoped.scope)
        assertEquals(UsageViewStatus.MEASURED, scoped.view.status)
        assertNotEquals(ParentUsageScope(parentB, device), scoped.scope)
    }

    @Test fun replyForAParentWhoSignedOutIsDiscarded() = runTest {
        val gate = CompletableDeferred<UsageReadReplyV1>()
        val m = model(object : ParentUsageApi { override suspend fun read(deviceId: String) = gate.await() })
        val job = launch { m.load(parentA, device) }
        runCurrent(); identity = null; gate.complete(reply()); job.join()
        assertNull(m.state.value)
        identity = parentA
    }

    @Test fun olderSlowReadCannotOverwriteANewerOne() = runTest {
        val slow = CompletableDeferred<UsageReadReplyV1>(); var calls = 0
        val m = model(object : ParentUsageApi { override suspend fun read(deviceId: String) = if (calls++ == 0) slow.await() else reply(sequence = 9) })
        val first = launch { m.load(parentA, device) }; runCurrent()
        m.load(parentA, device)
        slow.complete(reply(sequence = 1)); first.join()
        assertEquals(UsageViewStatus.MEASURED, m.state.value?.view?.status)
        assertEquals(1, m.state.value?.view?.days?.size)
        assertEquals(2, calls)
    }

    @Test fun accessLossReplacesThePreviousReport() = runTest {
        var denied = false
        val m = model(object : ParentUsageApi { override suspend fun read(deviceId: String) = if (denied) throw FamilyAccessDenied() else reply() })
        m.load(parentA, device); assertEquals(UsageViewStatus.MEASURED, m.state.value?.view?.status)
        denied = true; m.load(parentA, device)
        val view = checkNotNull(m.state.value).view
        assertEquals(UsageViewStatus.ACCESS_LOST, view.status); assertTrue(view.days.isEmpty()); assertTrue(view.apps.isEmpty())
        denied = false; m.load(parentA, device)
        assertEquals(UsageViewStatus.MEASURED, m.state.value?.view?.status)
    }

    @Test fun networkFailureKeepsOnlyTheLastConfirmedReportAsOffline() = runTest {
        var fail = false
        val m = model(object : ParentUsageApi { override suspend fun read(deviceId: String) = if (fail) throw java.io.IOException("down") else reply() })
        fail = true; m.load(parentA, device)
        assertEquals(UsageViewStatus.LOAD_FAILED, m.state.value?.view?.status)
        fail = false; m.load(parentA, device); fail = true; m.load(parentA, device)
        val view = checkNotNull(m.state.value).view
        assertEquals(UsageViewStatus.MEASURED, view.status); assertTrue(view.offline)
    }

    @Test fun clearDropsTheReportAndInvalidatesInFlightReads() = runTest {
        val gate = CompletableDeferred<UsageReadReplyV1>()
        val m = model(object : ParentUsageApi { override suspend fun read(deviceId: String) = gate.await() })
        val job = launch { m.load(parentA, device) }; runCurrent()
        m.clear(); gate.complete(reply()); job.join()
        assertNull(m.state.value)
    }

    @Test fun deviceSwitchNeverShowsTheOtherDevicesReport() = runTest {
        val other = "44444444-4444-4444-8444-444444444444"
        val m = model(object : ParentUsageApi { override suspend fun read(deviceId: String) = reply() })
        m.load(parentA, device)
        assertNotEquals(ParentUsageScope(parentA, other), m.state.value?.scope)
    }
}
