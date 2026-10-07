package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ParentBackgroundProcessorTest {
    private val owner = ParentIdentity("parent-a", "session-a")
    @Test fun coldTokenJobRestoresCapturedOptedOwnerBeforeFetchingCurrentToken() = runTest {
        var current: ParentIdentity? = null; var bindings = 0; var tokens = 0
        val processor = ParentBackgroundProcessor({ current }, { owner },
            { current = owner }, { bindings++ }, { expected -> assertEquals(owner, expected); tokens++ }, { true })
        assertTrue(processor.process(owner, null))
        assertEquals(owner, current)
        assertEquals(1, bindings)
        assertEquals(1, tokens)
    }
    @Test fun accountSwitchOrLocalOptOutRejectsQueuedTokenAndMessageWork() = runTest {
        var current: ParentIdentity? = owner; var optIn: ParentIdentity? = owner; var calls = 0
        val processor = ParentBackgroundProcessor({ current }, { optIn }, {}, {}, { calls++ }, { calls++; true })
        current = ParentIdentity(owner.userId, "new-session")
        assertFalse(processor.process(owner, null))
        assertFalse(processor.process(owner, envelope()))
        current = owner; optIn = null
        assertFalse(processor.process(owner, null))
        assertEquals(0, calls)
    }
    @Test fun stoppedColdRestoreCannotContinueToProviderOrRender() = runTest {
        var current: ParentIdentity? = null; var calls = 0
        val release = CompletableDeferred<Unit>()
        val processor = ParentBackgroundProcessor({ current }, { owner }, { release.await(); current = owner }, {}, { calls++ }, { calls++; true })
        val job = async { processor.process(owner, null) }
        runCurrent(); job.cancel(); release.complete(Unit); job.join()
        assertEquals(0, calls)
    }
}
