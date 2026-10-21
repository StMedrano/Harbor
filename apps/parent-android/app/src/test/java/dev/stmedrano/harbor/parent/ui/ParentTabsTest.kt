package dev.stmedrano.harbor.parent.ui

import dev.stmedrano.harbor.parent.family.DevicePublicV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParentTabsTest {
    private fun phone(id: String, child: String, status: String) =
        DevicePublicV1(1, id, "family", child, id, "standard", status, null, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")

    @Test fun tabsAreMapAlertsActivityFamilyAndNothingThatEnforces() {
        assertEquals(listOf("Map", "Alerts", "Controls", "Activity", "Family"), ParentTab.values().map { it.label })
    }

    @Test fun opensOnMapOnceAChildExistsAndOnFamilyBefore() {
        assertEquals(ParentTab.MAP, defaultParentTab(true))
        assertEquals(ParentTab.FAMILY, defaultParentTab(false))
    }

    @Test fun navItemsMarkExactlyTheSelectedTab() {
        val items = parentNavItems(ParentTab.ALERTS) {}
        assertEquals(listOf("Alerts"), items.filter { it.selected }.map { it.label })
    }

    @Test fun activeDevicePrefersActiveAndIgnoresRevokedAndOtherChildren() {
        val devices = listOf(phone("old", "a", "revoked"), phone("other", "b", "active"), phone("pending", "a", "pending"), phone("now", "a", "active"))
        assertEquals("now", activeDevice(devices, "a")?.id)
        assertEquals("pending", activeDevice(devices.filter { it.id != "now" }, "a")?.id)
        assertNull(activeDevice(listOf(phone("old", "a", "revoked")), "a"))
        assertNull(activeDevice(devices, "nobody"))
    }
}
