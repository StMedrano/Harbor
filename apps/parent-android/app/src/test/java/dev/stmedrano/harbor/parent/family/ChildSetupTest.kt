package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChildSetupTest {
    private val parent = ParentIdentity("parent-a", "session-a")

    @Test fun timeoutThenProcessRecreationReusesPersistedKeyAndPayload() = runTest {
        val dao = MemoryFamilyDao()
        val requests = mutableListOf<CreateChildRequest>()
        val api = object : TestParentApi() {
            override suspend fun createChild(request: CreateChildRequest): ChildV1 {
                requests += request
                if (requests.size == 1) error("server committed but response timed out")
                return super.createChild(request)
            }
        }
        val first = PendingChildCreation(api, dao, { parent }) { "first-key" }
        assertTrue(runCatching { first.submit(parent, "family-a", " Child ") }.isFailure)
        val restarted = PendingChildCreation(api, dao, { parent }) { "different-key" }
        val result = restarted.submit(parent, "family-a", "Child")
        assertEquals("Child", result.displayName)
        assertEquals("first-key", requests[0].idempotencyKey)
        assertEquals(requests[0], requests[1])
        assertNull(dao.getPending(parent.userId, "family-a"))
    }

    @Test fun changedPayloadRequiresExplicitCancelAndForeignSubjectCannotRetry() = runTest {
        val dao = MemoryFamilyDao()
        var calls = 0
        val api = object : TestParentApi() {
            override suspend fun createChild(request: CreateChildRequest): ChildV1 { calls++; error("offline") }
        }
        val operation = PendingChildCreation(api, dao, { parent }) { "key" }
        runCatching { operation.submit(parent, "family-a", "Child") }
        assertTrue(runCatching { operation.submit(parent, "family-a", "Other") }.isFailure)
        assertTrue(runCatching { operation.submit(ParentIdentity("parent-b", "session-b"), "family-a", "Child") }.isFailure)
        assertEquals(1, calls)
        operation.cancel(parent, "family-a")
        assertNull(dao.getPending(parent.userId, "family-a"))
    }
}
