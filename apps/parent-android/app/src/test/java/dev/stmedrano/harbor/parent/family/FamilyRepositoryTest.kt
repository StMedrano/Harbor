package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FamilyRepositoryTest {
    private val parent = ParentIdentity("parent-a", "session-a")

    @Test fun cacheIsBoundToCurrentSubjectAndFamily() = runTest {
        var current: ParentIdentity? = parent
        val repository = FamilyRepository(TestParentApi(), MemoryFamilyDao()) { current }
        repository.refresh(parent, "family-a")
        assertEquals(1234L, repository.cached(parent, "family-a")?.fetchedAt)
        assertNull(repository.cached(parent, "family-b"))
        current = ParentIdentity("parent-b", "session-b")
        assertNull(repository.cached(current!!, "family-a"))
        assertNull(repository.cached(parent, "family-a"))
    }

    @Test fun deniedMembershipClearsOnlyThatFamilyAndShowsFailure() = runTest {
        var denied = false
        val api = object : TestParentApi() {
            override suspend fun readFamily(familyId: String) = if (denied) throw FamilyAccessDenied() else snapshot(family = familyId)
        }
        val repository = FamilyRepository(api, MemoryFamilyDao()) { parent }
        repository.refresh(parent, "family-a")
        repository.refresh(parent, "family-b")
        denied = true
        try { repository.refresh(parent, "family-a") } catch (_: FamilyAccessDenied) { }
        assertNull(repository.cached(parent, "family-a"))
        assertNotNull(repository.cached(parent, "family-b"))
        assertFalse(repository.state.value.loading)
        assertEquals(FamilyFailure.ACCESS_DENIED, repository.state.value.failure)
    }

    @Test fun loadingAndNetworkFailureRetainAnExplicitCachedView() = runTest {
        val release = CompletableDeferred<Unit>()
        var failing = false
        val api = object : TestParentApi() {
            override suspend fun readFamily(familyId: String): dev.stmedrano.harbor.parent.data.FamilySnapshot {
                if (failing) { release.await(); error("offline") }
                return snapshot()
            }
        }
        val repository = FamilyRepository(api, MemoryFamilyDao()) { parent }
        repository.refresh(parent, "family-a")
        failing = true
        val request = async { runCatching { repository.refresh(parent, "family-a") } }
        kotlinx.coroutines.yield()
        assertTrue(repository.state.value.loading)
        release.complete(Unit)
        assertTrue(request.await().isFailure)
        assertTrue(repository.state.value.cached)
        assertEquals(FamilyFailure.NETWORK, repository.state.value.failure)
        assertEquals(1234L, repository.state.value.snapshot?.fetchedAt)
    }

    @Test fun foreignReplyCannotEnterCurrentUsersCache() = runTest {
        val api = object : TestParentApi() { override suspend fun readFamily(familyId: String) = snapshot(user = "parent-b") }
        val repository = FamilyRepository(api, MemoryFamilyDao()) { parent }
        assertTrue(runCatching { repository.refresh(parent, "family-a") }.isFailure)
        assertNull(repository.cached(parent, "family-a"))
    }
}
