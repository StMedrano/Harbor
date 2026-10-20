package dev.stmedrano.harbor.parent.alerts

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ParentAlertsModelTest {
    private val parentA = ParentIdentity("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "session-a")
    private val parentB = ParentIdentity("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "session-b")
    private val device = "22222222-2222-4222-8222-222222222222"
    private var n = 0
    private fun alert(at: String, category: AlertCategory) =
        MessageAlert("00000000-0000-4000-8000-%012d".format(++n), "WhatsApp", category, 1, at, false)
    private fun available(vararg alerts: MessageAlert) =
        AlertReadReplyV1(AlertReadState.AVAILABLE, true, false, "2026-10-10T17:59:00Z", "2026-10-10T17:59:30Z", alerts.toList(), null)

    @Test fun availableReplyBecomesAViewNewestFirst() = runTest {
        val reply = available(alert("2026-10-10T17:59:00Z", AlertCategory.BULLYING), alert("2026-10-10T17:50:00Z", AlertCategory.SELF_HARM))
        val model = ParentAlertsModel({ _, _, _ -> reply }, { parentA })
        model.load(parentA, device)
        val view = checkNotNull(model.state.value).view
        assertEquals(AlertsViewStatus.AVAILABLE, view.status)
        assertEquals("2026-10-10T17:59:00Z", view.alerts.first().at)
        assertEquals(true, view.notificationAccess)
        assertEquals(false, view.smsPermission)
    }

    @Test fun failureKeepsPreviousAlertsOfflineAndAccessLossDropsThem() = runTest {
        var mode = 0
        val reply = available(alert("2026-10-10T17:59:00Z", AlertCategory.BULLYING))
        val model = ParentAlertsModel({ _, _, _ -> when (mode) { 0 -> reply; 1 -> throw java.io.IOException("offline"); else -> throw FamilyAccessDenied() } }, { parentA })
        model.load(parentA, device)
        mode = 1; model.load(parentA, device)
        assertTrue(checkNotNull(model.state.value).view.offline)
        assertTrue(model.state.value!!.view.alerts.isNotEmpty())
        mode = 2; model.load(parentA, device)
        assertEquals(AlertsViewStatus.ACCESS_LOST, model.state.value?.view?.status)
        assertTrue(model.state.value!!.view.alerts.isEmpty())
        mode = 1; model.load(parentA, device)
        assertEquals("no stale alert returns after access loss", AlertsViewStatus.LOAD_FAILED, model.state.value?.view?.status)
    }

    @Test fun repliesForAnotherIdentityOrAfterClearAreDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        var identity: ParentIdentity? = parentA
        val model = ParentAlertsModel({ _, _, _ -> gate.await(); available() }, { identity })
        val job = async { model.load(parentA, device) }
        identity = parentB
        gate.complete(Unit); job.await()
        assertNull(model.state.value)
        identity = parentA
        model.load(parentA, device)
        assertNotNull(model.state.value)
        model.clear()
        assertNull(model.state.value)
    }

    @Test fun everyCategoryReadsAsAPossibilityNeverAVerdict() {
        for (category in AlertCategory.entries) {
            val label = alertCategoryLabel(category)
            assertTrue(label, label.startsWith("Possible "))
        }
    }

    @Test fun selfHarmShowsSupportiveGuidanceAndCrisisResources() {
        val guidance = checkNotNull(alertGuidance(AlertCategory.SELF_HARM))
        assertTrue(guidance.contains("988"))
        assertTrue(guidance.contains("741741"))
        assertTrue("guidance is not a verdict", guidance.contains("can be wrong"))
        for (other in AlertCategory.entries.filter { it != AlertCategory.SELF_HARM }) assertNull(alertGuidance(other))
    }

    @Test fun limitsNoticeIsHonestAboutWhatIsMissed() {
        assertTrue(ALERT_LIMITS_NOTICE.contains("mistakes"))
        assertTrue(ALERT_LIMITS_NOTICE.contains("never claims a message is safe"))
    }
}
