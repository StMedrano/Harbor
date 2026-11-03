package dev.stmedrano.harbor.parent.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HarborMotionTest {
    @Test fun goingForwardComesFromTheRightGoingBackFromTheLeftAndLevelRises() {
        assertEquals(1, arrivalDirection(0, 3))
        assertEquals(-1, arrivalDirection(3, 0))
        assertEquals(0, arrivalDirection(2, 2))
    }

    @Test fun theTintSitsExactlyOverEachTabAndPassesBetweenThem() {
        // Five tabs of 96 with gaps of 2 fill 488.
        assertEquals(96f, pillWidth(5, 488f, 2f), 0.001f)
        assertEquals(0f, pillLeft(0f, 5, 488f, 2f), 0.001f)
        assertEquals(98f, pillLeft(1f, 5, 488f, 2f), 0.001f)
        assertEquals(392f, pillLeft(4f, 5, 488f, 2f), 0.001f)
        assertEquals(49f, pillLeft(0.5f, 5, 488f, 2f), 0.001f)
    }
}
