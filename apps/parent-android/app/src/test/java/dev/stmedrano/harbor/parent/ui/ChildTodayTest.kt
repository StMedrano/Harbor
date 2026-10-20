package dev.stmedrano.harbor.parent.ui

import dev.stmedrano.harbor.parent.familymap.FamilyMapFix
import dev.stmedrano.harbor.parent.familymap.FamilyMapPerson
import dev.stmedrano.harbor.parent.familymap.PersonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChildTodayTest {
    private fun child(id: String, name: String, self: Boolean = false) = FamilyMapPerson(id, PersonKind.CHILD, name, self, null)
    private fun parent(id: String, name: String) = FamilyMapPerson(id, PersonKind.PARENT, name, false, FamilyMapFix("2026-10-20T12:00:00Z", 1.0, 2.0, 10))

    @Test fun greetingFollowsTheHourAndStaysPlainOvernight() {
        assertEquals("Good morning", timeGreeting(5))
        assertEquals("Good morning", timeGreeting(11))
        assertEquals("Good afternoon", timeGreeting(12))
        assertEquals("Good afternoon", timeGreeting(16))
        assertEquals("Good evening", timeGreeting(17))
        assertEquals("Good evening", timeGreeting(21))
        assertEquals("Hello", timeGreeting(22))
        assertEquals("Hello", timeGreeting(0))
        assertEquals("Hello", timeGreeting(4))
    }

    @Test fun theChildIsTheOneMarkedSelfAndNeverAParent() {
        val people = listOf(parent("p1", "Sam"), child("c1", "Ana"), child("c2", "Maya", self = true))
        assertEquals("Maya", childSelf(people)?.name)
        assertEquals(1, childColourIndex(people, checkNotNull(childSelf(people))))
    }

    @Test fun noOneIsGreetedUntilTheMapHasNamedThem() {
        assertNull(childSelf(emptyList()))
        assertNull(childSelf(listOf(parent("p1", "Sam"), child("c1", "Ana"))))
    }
}
