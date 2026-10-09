package dev.stmedrano.harbor.parent.usage

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.child.ChildSyncState
import dev.stmedrano.harbor.parent.ui.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UsageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val measured = UsageViewState(UsageViewStatus.MEASURED, UsageViewOrigin.PARENT_READ,
        observedAt = "2026-10-08T12:00:00Z", receivedAt = "2026-10-08T12:05:00Z", zoneId = "UTC", inventory = InventoryStatus.COMPLETE,
        days = listOf(UsageDayRow("2026-10-06", UsageQuality.OBSERVED, 3_600_000, "2026-10-06T00:00:00Z"),
            UsageDayRow("2026-10-07", UsageQuality.UNAVAILABLE, null, null),
            UsageDayRow("2026-10-08", UsageQuality.PARTIAL, 5_400_000, "2026-10-08T09:30:00Z")),
        apps = listOf(UsageAppRow("example.one", "Example One", 4_200_000), UsageAppRow("example.two", "Example Two", 3_000_000),
            UsageAppRow("ghost.pkg", null, 60_000), UsageAppRow("example.idle", "Idle App", null)),
        stale = true, offline = true)

    private fun show(state: UsageViewState) =
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { UsageReportScreen(state, onRefresh = {}) } } }

    private fun capture(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(compose.activity.getExternalFilesDir(null), name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun noReportAndLostPermissionNeverShowZeroOrApps() {
        val state = mutableStateOf(UsageViewState(UsageViewStatus.NO_REPORT, UsageViewOrigin.PARENT_READ))
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { UsageReportScreen(state.value) } } }
        compose.onNodeWithText("No report yet.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0m").assertDoesNotExist(); compose.onNodeWithText("Apps today").assertDoesNotExist()
        compose.runOnIdle { state.value = UsageViewState(UsageViewStatus.PERMISSION_REQUIRED, UsageViewOrigin.PARENT_READ, receivedAt = "2026-10-08T12:05:00Z") }
        compose.onNodeWithText("Usage Access is off. No screen time is measured, so totals are unknown, not zero.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("0m").assertDoesNotExist()
    }

    @Test fun measuredPartialStaleOfflineAreDistinguishedAndAppsAreReadOnly() {
        show(measured)
        compose.onNodeWithText("1h 30m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Partial day", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Out of date", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Can't reach Harbor", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Oct 7: unknown", substring = true).assertExists()
        compose.onNodeWithText("Apps").performClick()
        compose.onNodeWithText("Example One").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1h 10m").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ghost.pkg · name unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("None recorded").performScrollTo().assertIsDisplayed()
        listOf("Block", "Set limit", "Limit", "Blocked").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
    }

    @Test fun revokedAccessHidesThePreviousReport() {
        val state = mutableStateOf(measured)
        compose.setContent { HarborTheme { ParentApp(showBrand = false) { UsageReportScreen(state.value) } } }
        compose.onNodeWithText("1h 30m").assertIsDisplayed()
        compose.runOnIdle { state.value = UsageViewState(UsageViewStatus.ACCESS_LOST, UsageViewOrigin.PARENT_READ) }
        compose.onNodeWithText("You no longer have access to this device's usage.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1h 30m").assertDoesNotExist()
        compose.onNodeWithText("Example One").assertDoesNotExist()
    }

    @Test fun largeTextTodayAndAppsRenderInLightAndDark() {
        val dark = mutableStateOf(false)
        compose.setContent { HarborTheme(dark = dark.value) { ParentApp(showBrand = false) { UsageReportScreen(measured) } } }
        compose.onNodeWithText("1h 30m").performScrollTo().assertIsDisplayed()
        capture("usage-today-light.png")
        compose.onNodeWithText("Apps").performClick()
        compose.onNodeWithText("Example One").performScrollTo().assertIsDisplayed()
        capture("usage-apps-light.png")
        compose.runOnIdle { dark.value = true }
        compose.onNodeWithText("Example One").performScrollTo().assertIsDisplayed()
        capture("usage-apps-dark.png")
    }

    @Test fun consentIsExplicitAndStopIsAlwaysAvailable() {
        var started = 0; var stopped = 0; var opened = 0
        val ui = mutableStateOf(ChildUsageUi(UsageViewState(UsageViewStatus.SHARING_OFF, UsageViewOrigin.CHILD_PHONE), false, false,
            { started++ }, { stopped++ }, { opened++ }, {}))
        compose.setContent { HarborTheme { ParentApp(showBrand = false) {
            ChildDashboard(ChildSyncState.Stale(1000), ChildBinding("device-a", "family-a", "child-a"), {}, {}, usage = ui.value)
        } } }
        compose.onNodeWithText("does not read messages", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open Usage Access settings").assertDoesNotExist()
        assertEquals(0, started)
        compose.onNodeWithText("Start sharing screen time").performScrollTo().performClick()
        assertEquals(1, started)
        compose.runOnIdle { ui.value = ChildUsageUi(UsageViewState(UsageViewStatus.PERMISSION_REQUIRED, UsageViewOrigin.CHILD_PHONE), true, false, { started++ }, { stopped++ }, { opened++ }, {}) }
        compose.onNodeWithText("Open Usage Access settings").performScrollTo().performClick()
        assertEquals(1, opened)
        compose.onNodeWithText("Stop sharing and remove my report").performScrollTo().performClick()
        assertEquals(1, stopped)
        compose.onNodeWithText("Apps").performClick()
        compose.onNodeWithText("Usage Access is off", substring = true).assertIsDisplayed()
    }
}
