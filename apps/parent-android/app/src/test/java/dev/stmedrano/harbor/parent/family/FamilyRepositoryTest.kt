package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FamilyRepositoryTest {
    private val parent = ParentIdentity("parent-a", "session-a")

    @Test fun aFamilyIsNeverSavedToShowLater() = runTest {
        val dao = MemoryFamilyDao()
        val repository = FamilyRepository(TestParentApi(), dao) { parent }
        repository.refresh(parent, "family-a")
        assertEquals("family-a", repository.state.value.snapshot?.family?.id)
        assertTrue(dao.snapshots.isEmpty())
    }

    @Test fun copiesSavedByEarlierVersionsAreDeleted() = runTest {
        val dao = MemoryFamilyDao()
        dao.putSnapshot(dev.stmedrano.harbor.parent.data.FamilyCacheRow("parent-a", "family-a", "{}", 1L))
        FamilyRepository(TestParentApi(), dao) { parent }.forgetSavedFamilies()
        assertTrue(dao.snapshots.isEmpty())
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
        assertNull(repository.state.value.snapshot)
        assertFalse(repository.state.value.loading)
        assertEquals(FamilyFailure.ACCESS_DENIED, repository.state.value.failure)
    }

    @Test fun aFailedRefreshKeepsOnlyWhatThisRunAlreadyRead() = runTest {
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
        assertEquals(FamilyFailure.NETWORK, repository.state.value.failure)
        assertEquals(1234L, repository.state.value.snapshot?.fetchedAt)
    }

    @Test fun foreignReplyCannotEnterCurrentUsersCache() = runTest {
        val api = object : TestParentApi() { override suspend fun readFamily(familyId: String) = snapshot(user = "parent-b") }
        val repository = FamilyRepository(api, MemoryFamilyDao()) { parent }
        assertTrue(runCatching { repository.refresh(parent, "family-a") }.isFailure)
        assertNull(repository.state.value.snapshot)
    }

    @Test fun InFlightReadAfterClearCannotRestoreCacheOrVisibleState() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = object : TestParentApi() {
            override suspend fun readFamily(familyId: String): dev.stmedrano.harbor.parent.data.FamilySnapshot {
                release.await(); return snapshot(family = familyId)
            }
        }
        val repository = FamilyRepository(api, MemoryFamilyDao()) { parent }
        val request = async { runCatching { repository.refresh(parent, "family-a") } }
        kotlinx.coroutines.yield()
        repository.clearAll()
        release.complete(Unit)
        assertTrue(request.await().isFailure)
        assertNull(repository.state.value.snapshot)
    }

    @Test fun OlderFamilyReadCannotOverwriteNewSelection() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = object : TestParentApi() {
            override suspend fun readFamily(familyId: String): dev.stmedrano.harbor.parent.data.FamilySnapshot {
                if (familyId == "family-a") release.await()
                return snapshot(family = familyId)
            }
        }
        val repository = FamilyRepository(api, MemoryFamilyDao()) { parent }
        val old = async { runCatching { repository.refresh(parent, "family-a") } }
        kotlinx.coroutines.yield()
        repository.refresh(parent, "family-b")
        release.complete(Unit)
        assertTrue(old.await().isFailure)
        assertEquals("family-b", repository.state.value.snapshot?.family?.id)
    }
}
