package dev.stmedrano.harbor.parent.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.access.AccessReason
import dev.stmedrano.harbor.parent.access.AccessRequestItem
import dev.stmedrano.harbor.parent.access.AccessViewState
import dev.stmedrano.harbor.parent.access.AccessViewStatus
import dev.stmedrano.harbor.parent.alerts.AlertCategory
import dev.stmedrano.harbor.parent.alerts.AlertsViewState
import dev.stmedrano.harbor.parent.alerts.AlertsViewStatus
import dev.stmedrano.harbor.parent.alerts.MessageAlert
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.controls.*
import dev.stmedrano.harbor.parent.data.FamilySnapshot
import dev.stmedrano.harbor.parent.family.*
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renders the main screens in Night Harbor and Daybreak with synthetic data and saves each as a PNG in the app's
 * external files directory (design-<screen>-<theme>.png), for review against the Harbor Family design system. It takes
 * real screenshots through UiAutomation, without Espresso, so it also runs on the newest emulator images.
 */
class HarborDesignGalleryTest {
    private val stamp = "2026-01-01T00:00:00Z"
    private val kids = listOf(ChildV1(1, "a", "family", "Leo", stamp, stamp), ChildV1(1, "b", "family", "Maya", stamp, stamp),
        ChildV1(1, "c", "family", "Sam", stamp, stamp))
    private val phone = DevicePublicV1(1, "phone", "family", "a", "Pixel 8", "standard", "active", null, stamp, stamp)
    private val snapshot = FamilySnapshot(FamilyV1(1, "family", "The Riveras", "UTC", stamp, stamp),
        FamilyMemberV1(1, "m", "family", "parent", "owner", "active", stamp, stamp), kids, listOf(phone), 1,
        parents = listOf(dev.stmedrano.harbor.parent.data.FamilyParentV1("parent", "owner", "Alex Rivera"),
            dev.stmedrano.harbor.parent.data.FamilyParentV1("parent-2", "parent", "Jordan Rivera")))
    private val youtube = "com.google.android.youtube"
    private val tiktok = "com.zhiliaoapp.musically"
    private val now = ZonedDateTime.now(ZoneId.systemDefault())

    // Sign-in is left out: it sets FLAG_SECURE, so Android blanks any screenshot of it (FIELDS shows the same field).
    private enum class Screen { WELCOME, FIELDS, MAP, CHECK_IN, ALERTS, CONTROLS, ACTIVITY, FAMILY, PAIRING, CHILD_TODAY, CHILD_CHECK_IN, CHILD_ABOUT, TOASTS }

    private fun iso(minutesAgo: Long) = now.minusMinutes(minutesAgo).toInstant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
    private fun event(kind: dev.stmedrano.harbor.parent.activity.ActivityKind, minutesAgo: Long, minutes: Long? = null, app: String? = null, label: String? = null,
        reason: dev.stmedrano.harbor.parent.activity.BlockReason? = null) =
        dev.stmedrano.harbor.parent.activity.ActivityEvent("e$minutesAgo", kind, iso(minutesAgo), minutes?.let { iso(minutesAgo - it) }, app, label, reason)

    @Composable
    private fun Render(screen: Screen) {
        val parentBar: @Composable () -> Unit = {
            HarborBottomBar(parentNavItems(when (screen) {
                Screen.ALERTS -> ParentTab.ALERTS; Screen.CONTROLS -> ParentTab.CONTROLS; Screen.ACTIVITY -> ParentTab.ACTIVITY
                Screen.FAMILY, Screen.PAIRING -> ParentTab.FAMILY; else -> ParentTab.MAP
            }) {})
        }
        when (screen) {
            Screen.WELCOME -> ParentApp(showBrand = false, centered = true) { FamilyEntryScreen({}, {}) }
            Screen.FIELDS -> ParentApp(showBrand = false) {
                HarborScreenHeader("Parent account", "Welcome back", subtitle = "Sign in to see how your family is doing.")
                HarborField("alex@example.invalid", {}, "Email")
                HarborField("secret", {}, "Password", error = "Incorrect email or password.",
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailing = { androidx.compose.material3.TextButton({}) { androidx.compose.material3.Text("Show", color = harbor.acc) } })
                HarborField("", {}, "Pairing code", hint = "Six digits from your parent's Harbor app. A code works for 10 minutes.")
                HarborButton({}, androidx.compose.ui.Modifier.fillMaxWidth()) { androidx.compose.material3.Text("Sign in") }
            }
            Screen.CHECK_IN -> ParentApp(bottomBar = parentBar) {
                KidSwitcher(kids, "b", {}, {})
                ParentCheckInScreen(dev.stmedrano.harbor.parent.checkin.CheckInViewState(dev.stmedrano.harbor.parent.checkin.CheckInViewStatus.READY, listOf(
                    dev.stmedrano.harbor.parent.checkin.CheckInEntry("c1", iso(3), iso(-60 * 23), dev.stmedrano.harbor.parent.checkin.CheckInStatus.PENDING, null))), "Maya") {}
                ParentCheckInScreen(dev.stmedrano.harbor.parent.checkin.CheckInViewState(dev.stmedrano.harbor.parent.checkin.CheckInViewStatus.READY, listOf(
                    dev.stmedrano.harbor.parent.checkin.CheckInEntry("c2", iso(40), iso(-60 * 23), dev.stmedrano.harbor.parent.checkin.CheckInStatus.ANSWERED,
                        dev.stmedrano.harbor.parent.checkin.CheckInEntryReply(iso(35), dev.stmedrano.harbor.parent.checkin.CheckInPreset.OK, null)))), "Maya") {}
            }
            Screen.ACTIVITY -> ParentApp(bottomBar = parentBar) {
                val today = now.toLocalDate()
                ParentTabHeader(ParentTab.ACTIVITY, "Leo's Pixel 8")
                ParentActivityScreen(dev.stmedrano.harbor.parent.activity.ActivityViewState(dev.stmedrano.harbor.parent.activity.ActivityViewStatus.AVAILABLE, today, true,
                    listOf(event(dev.stmedrano.harbor.parent.activity.ActivityKind.APP_SESSION, 300, 42, youtube, "YouTube"), event(dev.stmedrano.harbor.parent.activity.ActivityKind.LIMIT_REACHED, 250, null, youtube, "YouTube"),
                        event(dev.stmedrano.harbor.parent.activity.ActivityKind.BLOCKED_OPEN, 200, null, tiktok, "TikTok", dev.stmedrano.harbor.parent.activity.BlockReason.BLOCKED_APP),
                        event(dev.stmedrano.harbor.parent.activity.ActivityKind.APP_SESSION, 120, 25, "com.whatsapp", "WhatsApp"), event(dev.stmedrano.harbor.parent.activity.ActivityKind.BEDTIME_STARTED, 30)),
                    listOf(dev.stmedrano.harbor.parent.activity.AppTotal(youtube, "YouTube", 42 * 60_000L), dev.stmedrano.harbor.parent.activity.AppTotal("com.whatsapp", "WhatsApp", 25 * 60_000L)),
                    screenTime = dev.stmedrano.harbor.parent.activity.ScreenTime(today.toString(), 80 * 60_000L, emptyList())), today, {}, {})
            }
            Screen.PAIRING -> ParentApp(bottomBar = parentBar) {
                FamilyScreen(FamilyState(snapshot), listOf(snapshot.family), "a", {}, {}, {}, {}, {}, {}, {})
                PairingSheet(PairingState("a", PairingCode("482913", iso(-10))), false, null, {}, {}, {})
            }
            Screen.CHILD_CHECK_IN -> ParentApp(bottomBar = { HarborBottomBar(childNavItems(CHECK_IN_TAB, withCheckIn = true) {}) }) {
                val (eyebrow, title) = childHeader(CHECK_IN_TAB)
                HarborScreenHeader(eyebrow, checkNotNull(title))
                CheckInScreen(ChildCheckInUi(dev.stmedrano.harbor.parent.checkin.CheckInUiState(loaded = true,
                    pending = dev.stmedrano.harbor.parent.checkin.CheckInPending("p1", iso(2), iso(-60 * 23)),
                    recent = listOf(dev.stmedrano.harbor.parent.checkin.CheckInRecent("r1", iso(60 * 26), iso(60 * 26 - 3), dev.stmedrano.harbor.parent.checkin.CheckInPreset.OK, true))), {}, {}))
            }
            Screen.CHILD_ABOUT -> ParentApp(bottomBar = { HarborBottomBar(childNavItems("About", withCheckIn = true) {}) }) {
                ChildDashboard(ChildSyncState.Stale(1000), ChildBinding("device-a", "family-a", "child-a"), {}, {},
                    checkIn = ChildCheckInUi(dev.stmedrano.harbor.parent.checkin.CheckInUiState(loaded = true), {}, {}),
                    controls = ChildControlsUi(ControlsV1(), true, emptyMap(), emptyMap(), now) {}, tab = "About")
            }
            Screen.MAP -> ParentApp(bottomBar = parentBar) {
                KidSwitcher(kids, "b", {}, {}, meta = "School · 2 min ago")
                ParentChildPage(ParentTab.MAP, kids[1], null, ParentChildData(), Color.Unspecified) {}
            }
            Screen.ALERTS -> ParentApp(bottomBar = parentBar) {
                KidSwitcher(kids, "a", {}, {})
                ParentChildPage(ParentTab.ALERTS, kids[0], phone, ParentChildData(alerts = AlertsViewState(AlertsViewStatus.AVAILABLE, true, true,
                    listOf(MessageAlert("x", "Messages", AlertCategory.BULLYING, 1, stamp, false),
                        MessageAlert("y", "Chat", AlertCategory.VIOLENCE_THREAT, 1, stamp, true)))), Color.Unspecified, null)
            }
            Screen.CONTROLS -> ParentApp(bottomBar = parentBar) {
                KidSwitcher(kids, "a", {}, {})
                val controls = ControlsV1(dailyLimit = DailyLimit(120), appLimits = listOf(AppLimit(youtube, 60)), blockedApps = listOf(tiktok),
                    bedtime = ControlsBedtime(true, "21:00", "07:00", Weekday.entries))
                ParentChildPage(ParentTab.CONTROLS, kids[0], phone, ParentChildData(
                    controls = ControlsViewState(ControlsViewStatus.AVAILABLE, controls, 3, applied = true),
                    onChangeControls = {}, onAnswerAccess = { _, _ -> },
                    accessRequests = AccessViewState(AccessViewStatus.AVAILABLE, listOf(
                        AccessRequestItem("r1", AccessReason.LIMIT_REACHED, youtube, "YouTube", now.minusMinutes(2).toInstant().toString(), "pending", null)))),
                    Color.Unspecified, null)
            }
            Screen.FAMILY -> ParentApp(bottomBar = parentBar) {
                FamilyScreen(FamilyState(snapshot), listOf(snapshot.family), "a", {}, {}, {}, {}, {}, {}, {}, onSettings = {}, onSecurity = {}, onOpenDoc = {})
            }
            Screen.CHILD_TODAY -> ParentApp(bottomBar = { HarborBottomBar(childNavItems("Today", withCheckIn = true) {}) }) {
                ChildDashboard(ChildSyncState.Stale(1000), ChildBinding("device-a", "family-a", "child-a"), {}, {},
                    controls = ChildControlsUi(ControlsV1(appLimits = listOf(AppLimit(youtube, 60)), blockedApps = listOf(tiktok)), true,
                        mapOf(tiktok to "TikTok", youtube to "YouTube"), mapOf(youtube to 25 * 60_000L), now) {}, tab = "Today")
            }
            Screen.TOASTS -> ParentApp(bottomBar = parentBar) {
                HarborScreenHeader("Feedback", "Toasts and notes", subtitle = "One toast at a time, above the dock.")
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    HarborToast(HarborToastMessage(HarborToastTone.SUCCESS, "Check-in request sent to Maya"), {})
                    HarborToast(HarborToastMessage(HarborToastTone.INFO, "Your child is asking for more time", "Answer it under Controls", "Open") {}, {})
                    HarborToast(HarborToastMessage(HarborToastTone.REVIEW, "Leo hit his 2h limit", action = "Add time") {}, {})
                    HarborToast(HarborToastMessage(HarborToastTone.URGENT, "Leo sent an SOS", action = "Open") {}, {})
                }
                HarborNote("A flag is not a confirmed issue.")
                HarborNote("Showing saved setup. Current status has not been confirmed.", HarborTone.WARN)
                HarborNote("You no longer have access to this device's alerts.", HarborTone.DANGER)
                HarborCard { HarborEmptyState("All caught up", art = HarborArt.LIGHTHOUSE, body = "New alerts appear here.") }
            }
        }
    }

    @Test fun savesEveryScreenInBothThemes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dir = instrumentation.targetContext.getExternalFilesDir(null)
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            for (theme in listOf(true, false)) for (s in Screen.entries) {
                scenario.onActivity { it.setContent { HarborTheme(dark = theme) { Render(s) } } }
                instrumentation.waitForIdleSync()
                Thread.sleep(2_500) // let a slow (software-rendered) emulator draw the new screen
                val image: Bitmap = instrumentation.uiAutomation.takeScreenshot()
                val file = File(dir, "design-${s.name.lowercase()}-${if (theme) "night" else "daybreak"}.png")
                file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                assertTrue(file.length() > 0)
            }
        }
    }
}
