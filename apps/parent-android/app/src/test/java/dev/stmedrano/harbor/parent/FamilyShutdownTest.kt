package dev.stmedrano.harbor.parent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

class FamilyShutdownTest {
    @Test fun everyStepRunsInOrderEvenWhenEarlierStepsFail() = runTest {
        val ran = mutableListOf<String>()
        val failure = runCatching {
            runShutdownSteps(
                { ran += "usage-job" },
                { ran += "usage-stop"; throw IllegalStateException("usage") },
                { ran += "notification-job" },
                { ran += "notifications"; throw IllegalArgumentException("notifications") },
                { ran += "scope" },
                { ran += "parent" },
            )
        }.exceptionOrNull()
        assertEquals(listOf("usage-job", "usage-stop", "notification-job", "notifications", "scope", "parent"), ran)
        assertEquals("usage", failure?.message)
        assertEquals(listOf("notifications"), failure?.suppressed?.map { it.message })
    }

    @Test fun cancellationOfTheCallerCannotSkipRemainingCleanup() = runTest {
        val ran = mutableListOf<String>()
        val caller = launch(start = CoroutineStart.LAZY) {
            runShutdownSteps({ ran += "first" }, { delay(10); ran += "second" }, { ran += "third" })
        }
        caller.start(); runCurrent()
        caller.cancel()
        advanceUntilIdle()
        assertEquals(listOf("first", "second", "third"), ran)
        assertTrue(caller.isCancelled)
    }

    @Test fun noFailureCompletesNormally() = runTest {
        var done = 0
        runShutdownSteps({ done++ }, { done++ })
        assertEquals(2, done)
    }
}
