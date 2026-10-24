package dev.stmedrano.harbor.parent.ui

import org.junit.Assert.*
import org.junit.Test

/** How a parent's invite code reads and when it is worth sending. The server decides whether a code is real. */
class ParentInviteTest {
    @Test fun aCodeReadsAsFourAndFour() {
        assertEquals("7K3M-Q9XZ", inviteCodeText("7K3MQ9XZ"))
        assertEquals("7K3M-Q9XZ", inviteCodeText("7K3M-Q9XZ"))
        assertEquals("short", inviteCodeText("short"))
    }

    @Test fun joinWaitsForEightCharactersAndIgnoresSpacingAndDashes() {
        assertFalse(inviteCodeReady(""))
        assertFalse(inviteCodeReady("7K3MQ9X"))
        assertTrue(inviteCodeReady("7K3MQ9XZ"))
        assertTrue(inviteCodeReady("7k3m-q9xz"))
        assertTrue(inviteCodeReady(" 7K3M Q9XZ "))
        assertFalse(inviteCodeReady("7K3MQ9XZ1"))
    }
}
