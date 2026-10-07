package dev.stmedrano.harbor.parent.notifications

import com.google.android.gms.tasks.TaskCompletionSource
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseTokenProviderTest {
    @Test fun CancelledDeleteMustSettleBeforeNextTokenFetch() = runTest {
        val deletion = TaskCompletionSource<Void>()
        val fetched = TaskCompletionSource<String>().also { it.setResult("synthetic-new-token") }
        var fetches = 0
        val autoInit = mutableListOf<Boolean>()
        val provider = AndroidFirebaseTokenProvider({ fetches++; fetched.task }, { deletion.task }, { autoInit += it })
        val old = async { provider.deleteToken() }; runCurrent()
        old.cancelAndJoin()
        val next = async { provider.token() }; runCurrent()
        assertEquals(0, fetches)
        assertFalse(next.isCompleted)
        deletion.setResult(null); runCurrent()
        assertEquals("synthetic-new-token", next.await())
        assertEquals(1, fetches)
        assertEquals(listOf(false, true), autoInit)
    }

    @Test fun TokenFailurePropagatesWithoutInventingRegistrationToken() = runTest {
        val fetched = TaskCompletionSource<String>().also { it.setException(IllegalStateException("provider unavailable")) }
        val deleted = TaskCompletionSource<Void>().also { it.setResult(null) }
        val provider = AndroidFirebaseTokenProvider({ fetched.task }, { deleted.task }, {})
        assertTrue(runCatching { provider.token() }.isFailure)
    }
}
