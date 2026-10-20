package dev.stmedrano.harbor.parent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ChildTabsTest {
    @Test fun checkInTabExistsOnlyWhenCheckInsAreAvailable() {
        assertEquals(listOf("Today", "Apps", "About"), childNavItems("Today") {}.map { it.label })
        assertEquals(listOf("Today", "Check in", "Apps", "About"), childNavItems("Today", withCheckIn = true) {}.map { it.label })
        assertEquals(listOf("Today", "Check in", "Apps", "About"), childTabs(true))
        assertEquals(listOf("Today", "Apps", "About"), childTabs(false))
    }
    @Test fun familyTabSitsBetweenCheckInAndApps() {
        assertEquals(listOf("Today", "Check in", "Family", "Apps", "About"), childTabs(true, true))
        assertEquals(listOf("Today", "Family", "Apps", "About"), childTabs(false, true))
        assertEquals(listOf("Today", "Check in", "Family", "Apps", "About"), childNavItems("Family", withCheckIn = true, withFamily = true) {}.map { it.label })
    }
    @Test fun selectionAndTapsFollowTheLabel() {
        val taps = mutableListOf<String>()
        val items = childNavItems("Check in", withCheckIn = true) { taps += it }
        assertEquals(listOf("Check in"), items.filter { it.selected }.map { it.label })
        items.forEach { it.onClick() }
        assertEquals(listOf("Today", "Check in", "Apps", "About"), taps)
    }
}
