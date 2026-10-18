package dev.stmedrano.harbor.parent.usage

import org.junit.Assert.*
import org.junit.Test

class UsageLocalReadTest {
    private fun state(consent: Boolean, pending: UsagePending? = null) = UsageStoredState("binding-a", "11111111-1111-4111-8111-111111111111", consent = consent, pending = pending)
    private val clear = PendingClear(ClearUsageV1(1, "11111111-1111-4111-8111-111111111111", 3), "{}", "a".repeat(64))
    private class FakeStore(private val onRead: () -> UsageStoredState?) : UsageStore {
        override fun read(bindingId: String) = onRead()
        override fun update(bindingId: String, change: (UsageStoredState) -> UsageStoredState) = throw UnsupportedOperationException()
        override fun clearPayload(bindingId: String) {}
        override fun eraseBinding(bindingId: String) {}
    }

    @Test fun lostCheckpointIsARecoverableViewStateNotACrash() {
        val read = FakeStore { throw UsageCheckpointLost() }.readForView("binding-a")
        assertNull(read.state); assertTrue(read.recovering)
        val kept = FakeStore { state(true) }.readForView("binding-a")
        assertEquals(state(true), kept.state); assertFalse(kept.recovering)
        assertEquals(LocalUsageRead(null, false), FakeStore { null }.readForView("binding-a"))
    }

    @Test fun recoveringChildNeverLooksLikeSharingIsOff() {
        val view = UsageViewState.fromLocal(null, UsageRuntimeStatus.DISABLED, true, 0L, recovering = true) { null }
        assertEquals(UsageViewStatus.NO_REPORT, view.status)
        assertTrue(view.recovering)
        assertTrue(usageNotices(view).single().startsWith("Restoring"))
    }

    @Test fun backgroundWorkContinuesUntilDeletionIsConfirmed() {
        assertTrue(state(true).needsBackgroundWork)
        assertTrue(state(false, clear).needsBackgroundWork)
        assertFalse(state(false).needsBackgroundWork)
        assertFalse(state(false, PendingReport(Json0.report, "{}", "a".repeat(64))).needsBackgroundWork)
    }

    @Test fun unconfirmedDeletionIsShownAsPendingNotAsErased() {
        val view = UsageViewState.fromLocal(state(false, clear), UsageRuntimeStatus.OFFLINE, true, 0L) { null }
        assertEquals(UsageViewStatus.SHARING_OFF, view.status)
        assertTrue(view.deletionPending)
        val notices = usageNotices(view)
        assertTrue(notices.any { it.contains("may still be visible to your parents") })
        assertTrue(notices.none { it == "Sharing is off. Nothing is measured or sent." })
        assertFalse(UsageViewState.fromLocal(state(false), UsageRuntimeStatus.DISABLED, true, 0L) { null }.deletionPending)
    }

    @Test fun childAppsListsTheLaunchableInventoryAndItsTruncation() {
        val inventory = InventoryResult.Observed(listOf(UsageInventoryApp("example.idle", "Idle App"), UsageInventoryApp("example.used", "Used App")), 0L, true)
        val aggregate = reduceUsage(emptyList(), UsageWindow(0L, 60_000L, "UTC"))
        val measured = state(true).copy(latestAggregate = aggregate)
        val view = UsageViewState.fromLocal(measured, UsageRuntimeStatus.READY, true, 0L, inventory = inventory) { null }
        assertEquals(InventoryStatus.TRUNCATED, view.inventory)
        assertEquals(listOf("example.idle", "example.used"), view.apps.map { it.packageName })
        assertTrue(view.apps.all { it.foregroundMs == null })
        assertEquals(InventoryStatus.UNAVAILABLE, UsageViewState.fromLocal(measured, UsageRuntimeStatus.READY, true, 0L, inventory = InventoryResult.Unavailable) { null }.inventory)
    }
}

private object Json0 {
    val report: UsageReportV1 = kotlinx.serialization.json.Json.decodeFromString(checkNotNull(javaClass.classLoader?.getResource("usage-report-v1.json")).readText())
}
