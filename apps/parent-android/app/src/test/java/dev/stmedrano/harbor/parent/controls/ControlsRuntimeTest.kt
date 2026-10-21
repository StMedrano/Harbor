package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.child.ChildApi
import dev.stmedrano.harbor.parent.child.ChildAuthSession
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildCredentials
import dev.stmedrano.harbor.parent.child.ChildReply
import dev.stmedrano.harbor.parent.child.ChildRequest
import dev.stmedrano.harbor.parent.child.ChildSigner
import dev.stmedrano.harbor.parent.child.DesiredSync
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ControlsRuntimeTest {
    private val lease = ProfileLease(ProfileRole.CHILD, "22222222-2222-4222-8222-222222222222", 1)
    private val raw = checkNotNull(javaClass.classLoader?.getResource("controls-v1.json")).readText()

    private class MemoryStore : ControlsStore {
        var value: AppliedControls? = null
        var saves = 0
        override fun read(bindingId: String) = value?.takeIf { it.bindingId == bindingId }
        override fun save(value: AppliedControls) { saves++; this.value = value }
        override fun eraseBinding(bindingId: String) { if (value?.bindingId == bindingId) value = null }
    }
    private class Harness(val store: MemoryStore = MemoryStore(), var current: Boolean = true) {
        val acks = mutableListOf<Long>()
        var syncs = 0
        var published: ControlsV1? = null
        val host = ChildControlsHost(store, { current }, { acks += it }, { syncs++ }, { published = it })
    }

    @Test fun appliesStoresPublishesAndAcknowledgesOnceWithOneFollowUpSync() = runTest {
        val h = Harness()
        h.host.start(lease)
        h.host.apply(lease, DesiredSync(3, raw))
        assertEquals(decodeControls(raw), h.published)
        assertEquals(3L, h.store.value?.version)
        assertEquals(listOf(3L), h.acks)
        assertEquals(1, h.syncs)
        // The same version again (the follow-up sync) acknowledges but neither rewrites nor syncs again.
        h.host.apply(lease, DesiredSync(3, raw))
        assertEquals(1, h.store.saves)
        assertEquals(1, h.syncs)
    }
    @Test fun noControlsObjectMeansNothingIsBlocked() = runTest {
        val h = Harness()
        h.host.start(lease)
        h.host.apply(lease, DesiredSync(1, null))
        assertEquals(ControlsV1(), h.published)
        assertEquals(listOf(1L), h.acks)
    }
    @Test fun invalidControlsAreNeverAppliedOrAcknowledged() = runTest {
        val h = Harness()
        h.host.start(lease)
        h.host.apply(lease, DesiredSync(2, raw))
        h.host.apply(lease, DesiredSync(3, raw.replaceFirst("{", "{\"webFilter\":true,")))
        h.host.apply(lease, DesiredSync(4, "{\"version\":1}"))
        assertEquals(decodeControls(raw), h.published)
        assertEquals(2L, h.store.value?.version)
        assertEquals(listOf(2L), h.acks)
    }
    @Test fun olderVersionsAndForeignLeasesAreIgnored() = runTest {
        val h = Harness()
        h.host.start(lease)
        h.host.apply(lease, DesiredSync(5, raw))
        h.host.apply(lease, DesiredSync(4, null))
        assertEquals(decodeControls(raw), h.published)
        h.host.apply(lease.copy(generation = 2), DesiredSync(6, null))
        h.current = false
        h.host.apply(lease, DesiredSync(7, null))
        assertEquals(5L, h.store.value?.version)
    }
    @Test fun storedPolicyIsEnforcedBeforeTheFirstSyncAndStopErasesOnlyWhenAsked() = runTest {
        val store = MemoryStore().apply { value = AppliedControls(lease.ownerId, 9, decodeControls(raw)) }
        val h = Harness(store)
        h.host.start(lease)
        assertEquals(decodeControls(raw), h.published)
        assertEquals(listOf(9L), h.acks)
        h.host.stop()
        assertNull(h.published)
        assertNotNull(store.value)
        h.host.start(lease)
        h.host.stop(eraseBindingId = lease.ownerId)
        assertNull(store.value)
    }
    @Test fun foregroundTimeClipsToTheWindowAndCountsAnOpenSession() {
        val midnight = 1_000_000L
        val now = midnight + 3_600_000L
        // A session that started before midnight, one inside, and one still open.
        val events = listOf(midnight - 60_000L to true, midnight + 60_000L to false,
            midnight + 600_000L to true, midnight + 900_000L to false, now - 120_000L to true)
        assertEquals(60_000L + 300_000L + 120_000L, foregroundMs(events, midnight, now))
        assertEquals(0L, foregroundMs(emptyList(), midnight, now))
        assertEquals(60_000L, foregroundMs(listOf(midnight to true, midnight + 60_000L to false, midnight + 70_000L to false), midnight, now))
    }
    @Test fun totalForegroundLeavesOutExcludedApps() {
        val t = listOf(
            dev.stmedrano.harbor.parent.activity.AppTransition(1_000, "a.b", true), dev.stmedrano.harbor.parent.activity.AppTransition(61_000, "a.b", false),
            dev.stmedrano.harbor.parent.activity.AppTransition(61_000, "c.d", true), dev.stmedrano.harbor.parent.activity.AppTransition(181_000, "c.d", false),
            dev.stmedrano.harbor.parent.activity.AppTransition(200_000, "e.f", true))
        assertEquals(60_000L + 120_000L + 100_000L, totalForegroundMs(t, 0, 300_000, emptySet()))
        assertEquals(60_000L + 100_000L, totalForegroundMs(t, 0, 300_000, setOf("c.d")))
    }
    @Test fun aNewPolicyTellsTheServiceAtOnce() {
        var told = 0
        ControlsBridge.onPolicyChanged = { told++ }
        try {
            ControlsBridge.publish(ControlsV1(pause = ControlsPause(true, null)))
            ControlsBridge.publish(ControlsV1(pause = ControlsPause(true, null)))
            ControlsBridge.publish(null)
            assertEquals(2, told)
        } finally { ControlsBridge.onPolicyChanged = null }
        assertEquals("Time's up for today", blockMessage(dev.stmedrano.harbor.parent.activity.BlockReason.LIMIT_REACHED, "YouTube", null, daily = true).first)
    }
    @Test fun blockWordsArePlainAndSpecific() {
        val policy = decodeControls(raw)
        assertEquals("TikTok is blocked", blockMessage(BlockReason.BLOCKED_APP, "TikTok", policy).first)
        assertTrue(blockMessage(BlockReason.BEDTIME, "YouTube", policy).second.contains("07:00"))
        assertEquals("Time's up for YouTube", blockMessage(BlockReason.LIMIT_REACHED, "YouTube", policy).first)
        assertTrue(blockMessage(BlockReason.PAUSED, "YouTube", policy).second.contains("when they resume"))
    }
    @Test fun syncSendsTheAcknowledgmentAndReturnsTheRawControls() = runTest {
        val requests = mutableListOf<ChildRequest>()
        val api = ChildApi("https://parent-ci.invalid", "sb_publishable_fixture", {
            requests += it; ChildReply(200, "{\"desiredState\":{\"controls\":$raw},\"desiredStateVersion\":4,\"commands\":[]}")
        }, { 1000L }, { "nonce" }, object : ChildSigner {
            override fun exists() = true
            override fun publicKeySpki() = "public-spki"
            override fun sign(bytes: ByteArray) = ByteArray(64) { 1 }
            override fun delete() {}
        })
        val session = ChildAuthSession(ChildCredentials("child-access", "child-refresh", 5000), "11111111-1111-4111-8111-111111111111", true)
        val binding = ChildBinding(lease.ownerId, "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
        val result = api.syncDesired(binding, session, 3)
        assertEquals(4L, result.version)
        assertEquals(decodeControls(raw), decodeControls(checkNotNull(result.controlsJson)))
        assertEquals("{\"acknowledgedDesiredStateVersion\":3}", requests.single().body.toString(Charsets.UTF_8))
        val none = ChildApi("https://parent-ci.invalid", "sb_publishable_fixture", {
            ChildReply(200, "{\"desiredState\":{},\"desiredStateVersion\":0,\"commands\":[]}")
        }, { 1000L }, { "nonce" }, object : ChildSigner {
            override fun exists() = true
            override fun publicKeySpki() = "public-spki"
            override fun sign(bytes: ByteArray) = ByteArray(64) { 1 }
            override fun delete() {}
        }).syncDesired(binding, session, null)
        assertNull(none.controlsJson)
    }
}
