package dev.stmedrano.harbor.parent.access

import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.controls.*
import dev.stmedrano.harbor.parent.notifications.ParentMessageParser
import dev.stmedrano.harbor.parent.notifications.parentPushText
import dev.stmedrano.harbor.parent.ui.askedAgo
import dev.stmedrano.harbor.parent.ui.withGrant
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class AccessRequestsTest {
    private val raw = checkNotNull(javaClass.classLoader?.getResource("access-request-v1.json")).readText()
    private val parent = ParentIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "session-a")
    private val device = "22222222-2222-4222-8222-222222222222"
    private fun item(id: String = "8a1f3c2e-5b7d-4e9a-9c1b-2d3e4f5a6b7c", status: String = "pending", reason: AccessReason = AccessReason.BLOCKED_APP) =
        AccessRequestItem(id, reason, "com.zhiliaoapp.musically", "TikTok", "2026-10-21T15:00:00Z", status,
            if (status == "approved" || status == "denied") "2026-10-21T15:01:00Z" else null)

    @Test fun theSharedFixtureRoundTripsAndCarriesNoFreeText() {
        val request = Json.decodeFromString(AccessRequestV1.serializer(), raw)
        assertEquals(AccessReason.LIMIT_REACHED, request.reason)
        assertEquals(Json.parseToJsonElement(raw), Json.parseToJsonElement(encodeAccessRequest(request)))
        assertEquals(setOf("version", "requestId", "reason", "packageName", "appLabel"), Json.parseToJsonElement(raw).jsonObject.keys)
        assertThrows(IllegalArgumentException::class.java) { encodeAccessRequest(request.copy(appLabel = "You\nTube")) }
        assertThrows(IllegalArgumentException::class.java) { encodeAccessRequest(request.copy(packageName = "YouTube")) }
        assertEquals("YouTube", accessLabel(" You\u0000Tube "))
        assertEquals("This app", accessLabel("\n"))
        assertEquals(64, accessLabel("x".repeat(100)).length)
    }

    @Test fun reasonsWordsAndButtons() {
        assertEquals(AccessReason.TIME_UP, AccessReason.of(BlockReason.LIMIT_REACHED, daily = true))
        assertEquals(AccessReason.LIMIT_REACHED, AccessReason.of(BlockReason.LIMIT_REACHED, daily = false))
        assertEquals(AccessReason.PAUSED, AccessReason.of(BlockReason.PAUSED, daily = false))
        assertEquals("Use TikTok (blocked)", accessRequestTitle(item()))
        assertEquals("15 more minutes of TikTok", accessRequestTitle(item(reason = AccessReason.LIMIT_REACHED)))
        assertEquals("Ask to use TikTok", askButtonText(AccessReason.BLOCKED_APP, "TikTok"))
        assertEquals("Ask for 15 more minutes", askButtonText(AccessReason.BEDTIME, "TikTok"))
    }

    @Test fun parentReadIsStrict() {
        val ok = """{"requests":[{"requestId":"8a1f3c2e-5b7d-4e9a-9c1b-2d3e4f5a6b7c","reason":"paused","packageName":"com.a.b","appLabel":"A",""" +
            """"requestedAt":"2026-10-21T15:00:00Z","status":"approved","decidedAt":"2026-10-21T15:01:00Z"}]}"""
        assertEquals("approved", decodeAccessRequests(ok).single().status)
        assertThrows(Exception::class.java) { decodeAccessRequests(ok.replace("\"approved\"", "\"maybe\"")) }
        assertThrows(Exception::class.java) { decodeAccessRequests(ok.replace(""""decidedAt":"2026-10-21T15:01:00Z"""", """"decidedAt":null""")) }
        assertThrows(Exception::class.java) { decodeAccessRequests(ok.replace("\"appLabel\":\"A\"", "\"appLabel\":\"A\",\"note\":\"x\"")) }
        assertEquals("limited", decodeAccessRequestReply("""{"requestId":"8a1f3c2e-5b7d-4e9a-9c1b-2d3e4f5a6b7c","status":"limited","requestedAt":"2026-10-21T15:00:00Z"}""").status)
    }

    @Test fun childAskRemembersForAnHourAndReportsLimitsAndFailures() = runTest {
        var now = 0L
        var reply = """{"requestId":"8a1f3c2e-5b7d-4e9a-9c1b-2d3e4f5a6b7c","status":"pending","requestedAt":"2026-10-21T15:00:00Z"}"""
        var sent: String? = null
        val asker = ChildAccessAsker({ body -> sent = body; reply }) { now }
        assertFalse(asker.askedRecently("com.google.android.youtube"))
        assertEquals(AskResult.SENT, asker.ask(AccessReason.LIMIT_REACHED, "com.google.android.youtube", "YouTube"))
        assertTrue(checkNotNull(sent).contains("\"reason\":\"limit_reached\""))
        assertTrue(asker.askedRecently("com.google.android.youtube"))
        now = ACCESS_REQUEST_TTL_MS + 1
        assertFalse(asker.askedRecently("com.google.android.youtube"))
        reply = reply.replace("pending", "limited")
        assertEquals(AskResult.LIMITED, asker.ask(AccessReason.PAUSED, "com.whatsapp", "WhatsApp"))
        assertFalse(asker.askedRecently("com.whatsapp"))
        assertEquals(AskResult.FAILED, ChildAccessAsker({ error("offline") }) { 0L }.ask(AccessReason.PAUSED, "com.whatsapp", "WhatsApp"))
    }

    private class FakeApi(var requests: List<AccessRequestItem>) : ParentAccessApi {
        val decisions = mutableListOf<Pair<String, Boolean>>()
        var closed = false
        override suspend fun read(deviceId: String) = requests
        override suspend fun decide(requestId: String, approve: Boolean) {
            if (closed) throw AccessRequestClosed()
            decisions += requestId to approve
            requests = requests.map { if (it.requestId == requestId) it.copy(status = if (approve) "approved" else "denied", decidedAt = "2026-10-21T15:01:00Z") else it }
        }
    }

    @Test fun yesWritesTheGrantBeforeTheAnswerAndAFailedGrantSendsNoAnswer() = runTest {
        val api = FakeApi(listOf(item()))
        val model = ParentAccessModel(api) { parent }
        model.load(parent, device)
        assertEquals(1, checkNotNull(model.state.value).view.pending.size)
        val order = mutableListOf<String>()
        assertFalse(model.answer(parent, device, item(), approve = true) { order += "grant"; false })
        assertTrue(api.decisions.isEmpty())
        assertNotNull(checkNotNull(model.state.value).view.problem)
        assertTrue(model.answer(parent, device, item(), approve = true) { order += "grant"; true })
        assertEquals(listOf("grant", "grant"), order)
        assertEquals(listOf(item().requestId to true), api.decisions)
        assertTrue(checkNotNull(model.state.value).view.pending.isEmpty())
        assertNull(checkNotNull(model.state.value).view.problem)
    }

    @Test fun noNeedsNoGrantAndAClosedRequestSaysSo() = runTest {
        val api = FakeApi(listOf(item()))
        val model = ParentAccessModel(api) { parent }
        model.load(parent, device)
        api.closed = true
        assertFalse(model.answer(parent, device, item(), approve = false) { error("no grant for a no") })
        assertEquals("That request was already answered or has expired.", checkNotNull(model.state.value).view.problem)
        api.closed = false
        assertTrue(model.answer(parent, device, item(), approve = false) { error("no grant for a no") })
        assertEquals(listOf(item().requestId to false), api.decisions)
        model.clear()
        assertNull(model.state.value)
    }

    @Test fun grantsOverrideEveryRuleUntilTheyEnd() {
        val zone = ZoneId.of("America/Chicago")
        val now = ZonedDateTime.of(2026, 10, 21, 22, 0, 0, 0, zone)
        val nowMs = now.toInstant().toEpochMilli()
        val tiktok = "com.zhiliaoapp.musically"
        var policy = ControlsV1(pause = ControlsPause(true, null), bedtime = ControlsBedtime(true, "21:00", "07:00", Weekday.entries),
            blockedApps = listOf(tiktok), dailyLimit = DailyLimit(15))
        assertEquals(Decision.Block(BlockReason.PAUSED), PolicyEngine.decide(tiktok, now, policy, 0, emptySet(), 999 * 60_000L))
        policy = withGrant(policy, tiktok, nowMs + ACCESS_GRANT_MS, nowMs)
        validateControls(policy)
        assertEquals(Decision.Allow, PolicyEngine.decide(tiktok, now, policy, 0, emptySet(), 999 * 60_000L))
        assertEquals(ACCESS_GRANT_MS, PolicyEngine.grantRemainingMs(tiktok, now, policy))
        assertTrue(blockLifted(tiktok, BlockReason.PAUSED, policy, now))
        assertEquals(Decision.Block(BlockReason.PAUSED), PolicyEngine.decide(tiktok, now.plusMinutes(16), policy, 0, emptySet(), 0))
        assertFalse(blockLifted(tiktok, BlockReason.BLOCKED_APP, policy, now.plusMinutes(16)))
        assertFalse(blockLifted(tiktok, BlockReason.BLOCKED_APP, null, now))
        // A new yes replaces the old one for the same app, and ended ones are dropped.
        val later = withGrant(withGrant(policy, "com.whatsapp", nowMs - 1, nowMs - 60_000), tiktok, nowMs + 2 * ACCESS_GRANT_MS, nowMs)
        assertEquals(listOf(tiktok), later.grants.map { it.packageName })
        assertEquals(Instant.ofEpochMilli(nowMs + 2 * ACCESS_GRANT_MS).toString(), later.grants.single().until)
        assertEquals(CONTROLS_MAX_GRANTS, (0 until 30).fold(ControlsV1()) { c, i -> withGrant(c, "com.app.n$i", nowMs + 60_000, nowMs) }.grants.size)
    }

    @Test fun grantsDecodeStrictlyAndOlderPoliciesHaveNone() {
        val fixture = checkNotNull(javaClass.classLoader?.getResource("controls-v1.json")).readText()
        assertEquals(listOf(Grant("com.google.android.youtube", "2026-10-20T21:15:00Z")), decodeControls(fixture).grants)
        assertTrue(decodeControls(fixture.replace(Regex(""",\s*"grants"\s*:\s*\[[^\]]*\]"""), "")).grants.isEmpty())
        assertThrows(Exception::class.java) { decodeControls(fixture.replace("\"until\"", "\"reason\":\"x\",\"until\"")) }
        assertThrows(IllegalArgumentException::class.java) {
            validateControls(ControlsV1(grants = listOf(Grant("com.a.b", "2026-10-20T21:15:00Z"), Grant("com.a.b", "2026-10-20T22:15:00Z"))))
        }
    }

    @Test fun pushKindsAndWords() {
        val route = """{"version":1,"kind":"device.access.requested","familyId":"33333333-3333-4333-8333-333333333333",""" +
            """"childId":"44444444-4444-4444-8444-444444444444","deviceId":"22222222-2222-4222-8222-222222222222","resourceId":"8a1f3c2e-5b7d-4e9a-9c1b-2d3e4f5a6b7c"}"""
        for (kind in listOf("device.access.requested", "device.access.approved", "device.access.denied")) {
            assertNotNull(ParentMessageParser.parseRoute(route.replace("device.access.requested", kind)))
        }
        assertEquals("Your child is asking for more time. Open Harbor to answer.", parentPushText("device.access.requested"))
        assertEquals("just now", askedAgo("2026-10-21T15:00:00Z", Instant.parse("2026-10-21T15:00:30Z").toEpochMilli()))
        assertEquals("12 min ago", askedAgo("2026-10-21T15:00:00Z", Instant.parse("2026-10-21T15:12:30Z").toEpochMilli()))
        assertEquals("2 h ago", askedAgo("2026-10-21T15:00:00Z", Instant.parse("2026-10-21T17:05:00Z").toEpochMilli()))
    }
}
