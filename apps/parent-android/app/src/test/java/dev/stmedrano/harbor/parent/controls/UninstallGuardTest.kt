package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.access.AccessReason
import dev.stmedrano.harbor.parent.access.AccessRequestItem
import dev.stmedrano.harbor.parent.access.accessRequestTitle
import dev.stmedrano.harbor.parent.activity.ActivityEvent
import dev.stmedrano.harbor.parent.activity.ActivityKind
import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.activity.activityLine
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Removing an app needs a parent's OK while app controls run. Synthetic packages and times only. */
class UninstallGuardTest {
    private val installer = "com.google.android.packageinstaller"
    private val noon = ZonedDateTime.of(2026, 10, 23, 12, 0, 0, 0, ZoneId.of("UTC"))

    @Test fun onlyTheInstallersRemovalScreensAreGuardedAndOtherAppsScreensAreNeverRead() {
        val screens = RemovalScreens(UninstallGuard.INSTALLERS + "com.example.remover", setOf("com.example.remover.Confirm"))
        assertTrue(screens.isRemoval(installer) { "com.android.packageinstaller.UninstallerActivity" })
        assertTrue(screens.isRemoval(installer) { "com.android.packageinstaller.v2.ui.UninstallLaunch" })
        assertTrue(screens.isRemoval("com.example.remover") { "com.example.remover.Confirm" })
        // Installing an app is not guarded.
        assertFalse(screens.isRemoval(installer) { "com.android.packageinstaller.PackageInstallerActivity" })
        assertFalse(screens.isRemoval(installer) { null })
        var read = false
        assertFalse(screens.isRemoval("com.example.chat") { read = true; "com.example.chat.UninstallTipsActivity" })
        assertFalse("another app's screen must not be read", read)
    }

    @Test fun aParentsYesOpensRemovalAndAPhoneThatIsNotAPairedChildIsNeverCovered() {
        assertFalse(UninstallGuard.covers(installer, noon, null))
        assertTrue(UninstallGuard.covers(installer, noon, ControlsV1()))
        val yes = ControlsV1(grants = listOf(Grant(installer, noon.plusMinutes(15).toInstant().toString())))
        assertFalse(UninstallGuard.covers(installer, noon, yes))
        assertTrue(UninstallGuard.covers(installer, noon.plusMinutes(16), yes))
    }

    @Test fun theParentReadsARemovalRequestAndTimelineEntryInPlainWords() {
        val item = AccessRequestItem("r", AccessReason.BLOCKED_APP, installer, UninstallGuard.LABEL, noon.toInstant().toString(), "pending", null)
        assertEquals("Remove an app (it could be Harbor)", accessRequestTitle(item))
        assertEquals("Use YouTube (blocked)", accessRequestTitle(item.copy(packageName = "com.google.android.youtube", appLabel = "YouTube")))
        val event = ActivityEvent("e", ActivityKind.BLOCKED_OPEN, noon.toInstant().toString(), null, installer, UninstallGuard.LABEL, BlockReason.BLOCKED_APP)
        assertEquals("Tried to remove an app (needs your OK)", activityLine(event))
        assertEquals("Tried to open TikTok (blocked)", activityLine(event.copy(packageName = "com.zhiliaoapp.musically", appLabel = "TikTok")))
    }
}
