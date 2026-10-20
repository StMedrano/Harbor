package dev.stmedrano.harbor.parent.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoredAppsTest {
    @Test fun allowlistMatchesExactPackagesAndIgnoresEverythingElse() {
        for (pkg in listOf("com.whatsapp", "com.facebook.orca", "com.instagram.android",
            "com.zhiliaoapp.musically", "com.snapchat.android", "com.discord", "org.telegram.messenger",
            "com.twitter.android", "com.google.android.apps.messaging")) {
            assertTrue(pkg, MonitoredApps.isMonitored(pkg))
            assertEquals(pkg, MonitoredApps.label(pkg)?.isNotEmpty(), true)
        }
        for (pkg in listOf("com.whatsapp.fake", "com.bank.app", "com.google.android.gm", "", "com.discordx")) {
            assertFalse(pkg, MonitoredApps.isMonitored(pkg))
            assertEquals(null, MonitoredApps.label(pkg))
        }
    }

    @Test fun expectedAppsAreAllCovered() {
        assertEquals(
            setOf("Messages", "WhatsApp", "Facebook", "Messenger", "Instagram", "TikTok", "Snapchat", "Discord", "Telegram", "X"),
            MonitoredApps.labels().toSet(),
        )
    }
}

class NotificationDedupTest {
    @Test fun firstSightingIsTrueThenFalseForTheSameKey() {
        val dedup = NotificationDedup(capacity = 4)
        assertTrue(dedup.firstSighting("a"))
        assertFalse(dedup.firstSighting("a"))
        assertTrue(dedup.firstSighting("b"))
        assertFalse(dedup.firstSighting("b"))
    }

    @Test fun keysFallOutOnceCapacityIsExceeded() {
        val dedup = NotificationDedup(capacity = 2)
        assertTrue(dedup.firstSighting("a"))
        assertTrue(dedup.firstSighting("b"))
        assertTrue(dedup.firstSighting("c")) // evicts "a"
        assertTrue(dedup.firstSighting("a")) // "a" is new again after eviction
    }
}

class AlertCaptureCoreTest {
    private val core = AlertCaptureCore(now = { 1_700_000_000_000L }, newId = { "11111111-1111-4111-8111-111111111111" })

    @Test fun nonAllowlistedPackageProducesNothing() {
        assertTrue(core.capture("com.bank.app", "send nudes", isGroupSummary = false, fromSavedContact = false).isEmpty())
        assertTrue(core.capture("com.whatsapp.fake", "i want to die", isGroupSummary = false, fromSavedContact = false).isEmpty())
    }

    @Test fun groupSummaryAndBlankTextProduceNothing() {
        assertTrue(core.capture("com.whatsapp", "send nudes", isGroupSummary = true, fromSavedContact = false).isEmpty())
        assertTrue(core.capture("com.whatsapp", "   ", isGroupSummary = false, fromSavedContact = false).isEmpty())
        assertTrue(core.capture("com.whatsapp", null, isGroupSummary = false, fromSavedContact = false).isEmpty())
    }

    @Test fun benignMessageFromAMonitoredAppProducesNothing() {
        assertTrue(core.capture("com.discord", "see you at practice at five", false, fromSavedContact = true).isEmpty())
    }

    @Test fun concernRaisesOneValidatedAlertPerCategoryWithAppLabelAndNoText() {
        val alerts = core.capture("com.instagram.android", "i want to die and i will kill you", false, fromSavedContact = false)
        assertEquals(listOf(AlertCategory.SELF_HARM, AlertCategory.VIOLENCE_THREAT), alerts.map { it.category })
        for (a in alerts) {
            assertEquals("Instagram", a.appLabel)
            assertEquals(2, a.ruleSetVersion)
            assertFalse(a.savedContact)
            validateMessageAlert(a) // passes the contract's per-alert checks
        }
    }

    @Test fun savedContactSuppressesTheUnknownAdultCategory() {
        assertTrue(core.capture("com.snapchat.android", "how old are you really", false, fromSavedContact = true).isEmpty())
        assertEquals(
            listOf(AlertCategory.UNKNOWN_ADULT_CONTACT),
            core.capture("com.snapchat.android", "how old are you really", false, fromSavedContact = false).map { it.category },
        )
    }

    @Test fun smsIsAlwaysLabelledMessages() {
        val alerts = core.captureSms("send nudes", fromSavedContact = false)
        assertEquals(listOf(AlertCategory.ADULT_CONTENT), alerts.map { it.category })
        assertEquals("Messages", alerts.single().appLabel)
    }

    /** No field of any produced alert carries the message text. */
    @Test fun noAlertFieldContainsTheInputText() {
        val text = "i want to kill myself and buy weed"
        val alerts = core.capture("com.whatsapp", text, false, fromSavedContact = false)
        assertTrue(alerts.isNotEmpty())
        for (a in alerts) {
            val rendered = listOf(a.id, a.appLabel, a.category.name, a.ruleSetVersion.toString(), a.at, a.savedContact.toString())
                .joinToString("|").lowercase()
            assertFalse(rendered.contains(text.lowercase()))
            for (word in text.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }) assertFalse(word, rendered.contains(word))
        }
    }
}

class AlertCaptureAnyTest {
    private val core = AlertCaptureCore(now = { 1_700_000_000_000L })
    @Test fun sameConcernInSeveralFieldsIsOneAlert() {
        val alerts = core.captureAny("com.google.android.apps.messaging", listOf("send nudes", "send nudes", null, "see you at five"), false, false)
        assertEquals(listOf(AlertCategory.ADULT_CONTENT), alerts.map { it.category })
    }
    @Test fun aBenignSummaryDoesNotHideAConcerningNewestMessage() {
        val alerts = core.captureAny("com.whatsapp", listOf("2 new messages", null, "you are stupid"), false, false)
        assertEquals(listOf(AlertCategory.BULLYING), alerts.map { it.category })
    }
    @Test fun differentConcernsInDifferentFieldsAreAllReported() {
        val alerts = core.captureAny("com.whatsapp", listOf("i want to die", "bring a gun tomorrow"), false, false)
        assertEquals(setOf(AlertCategory.SELF_HARM, AlertCategory.VIOLENCE_THREAT), alerts.map { it.category }.toSet())
    }
}

class AlertCaptureBridgeTest {
    private fun alert(category: AlertCategory = AlertCategory.BULLYING, label: String = "Messages", id: String = java.util.UUID.randomUUID().toString()) =
        MessageAlert(id, label, category, 2, "2026-10-20T16:00:00Z", false)
    @org.junit.Before fun reset() = AlertCaptureBridge.resetForTest()
    @org.junit.After fun after() = AlertCaptureBridge.resetForTest()

    @Test fun alertsRaisedBeforeTheRuntimeStartsAreHandedOverOnInstall() {
        val got = mutableListOf<MessageAlert>()
        AlertCaptureBridge.submit(listOf(alert()), nowMs = 1_000)
        AlertCaptureBridge.install({ got += it }, nowMs = 5_000)
        assertEquals(1, got.size)
    }
    @Test fun heldAlertsExpire() {
        val got = mutableListOf<MessageAlert>()
        AlertCaptureBridge.submit(listOf(alert()), nowMs = 1_000)
        AlertCaptureBridge.install({ got += it }, nowMs = 1_000 + AlertCaptureBridge.HOLD_MS + 1)
        assertTrue(got.isEmpty())
    }
    @Test fun clearingDropsHeldAlertsSoANonChildPhoneKeepsNothing() {
        val got = mutableListOf<MessageAlert>()
        AlertCaptureBridge.submit(listOf(alert()), nowMs = 1_000)
        AlertCaptureBridge.clear()
        AlertCaptureBridge.install({ got += it }, nowMs = 2_000)
        assertTrue(got.isEmpty())
    }
    @Test fun holdingIsBounded() {
        val got = mutableListOf<MessageAlert>()
        repeat(AlertCaptureBridge.HOLD_MAX + 10) { i ->
            AlertCaptureBridge.submit(listOf(alert(label = "App$i")), nowMs = 1_000L + i)
        }
        AlertCaptureBridge.install({ got += it }, nowMs = 2_000)
        assertEquals(AlertCaptureBridge.HOLD_MAX, got.size)
    }
    @Test fun theSmsAndItsNotificationAreOneAlert() {
        val got = mutableListOf<MessageAlert>()
        AlertCaptureBridge.install({ got += it }, nowMs = 0)
        AlertCaptureBridge.submit(listOf(alert()), nowMs = 10_000)
        AlertCaptureBridge.submit(listOf(alert()), nowMs = 11_500)
        assertEquals(1, got.size)
    }
    @Test fun theSameConcernAgainLaterIsANewAlert() {
        val got = mutableListOf<MessageAlert>()
        AlertCaptureBridge.install({ got += it }, nowMs = 0)
        AlertCaptureBridge.submit(listOf(alert()), nowMs = 10_000)
        AlertCaptureBridge.submit(listOf(alert()), nowMs = 10_000 + AlertCaptureBridge.DUPLICATE_WINDOW_MS + 1)
        assertEquals(2, got.size)
    }
    @Test fun otherCategoriesAndAppsAreNotDuplicates() {
        val got = mutableListOf<MessageAlert>()
        AlertCaptureBridge.install({ got += it }, nowMs = 0)
        AlertCaptureBridge.submit(listOf(alert(AlertCategory.BULLYING), alert(AlertCategory.SELF_HARM), alert(AlertCategory.BULLYING, label = "WhatsApp")), nowMs = 10_000)
        assertEquals(3, got.size)
    }
}
