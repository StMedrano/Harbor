package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.auth.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

class FamilyViewModelTest {
    private val identity = ParentIdentity("parent-a", "session-a")
    private fun store() = SecureAuthStore(object : AuthValues {
        val values = mutableMapOf<String, String>()
        override fun read(key: String) = values[key]
        override fun write(key: String, value: String?) { if (value == null) values.remove(key) else values[key] = value }
        override fun clear() = values.clear()
    }, object : AuthCipher {
        override fun encrypt(slot: String, value: ByteArray) = value
        override fun decrypt(slot: String, value: ByteArray) = value
    })

    private fun model(api: TestParentApi): Pair<FamilyViewModel, FamilyRepository> {
        val dao = MemoryFamilyDao(); val repository = FamilyRepository(api, dao) { identity }
        return FamilyViewModel(api, repository, PendingChildCreation(api, dao, { identity }), PairingModel(api, { identity }), store(), { identity }) to repository
    }

    @Test fun leavingShowsTheNextFamilyThisParentBelongsTo() = runTest {
        var families = listOf(snapshot(family = "family-a").family, snapshot(family = "family-b").family)
        val left = mutableListOf<String>()
        val (model, repository) = model(object : TestParentApi() {
            override suspend fun listFamilies() = families
            override suspend fun leaveFamily(familyId: String): LeaveOutcome {
                left += familyId; families = families.filter { it.id != familyId }; return LeaveOutcome.LEFT
            }
        })
        model.load(identity)
        assertEquals("family-a", repository.state.value.snapshot?.family?.id)
        assertEquals(LeaveOutcome.LEFT, model.leaveFamily(identity, "family-a"))
        assertEquals(listOf("family-a"), left)
        assertEquals("family-b", repository.state.value.snapshot?.family?.id)
        assertEquals(listOf("family-b"), model.state.value.families.map { it.id })
    }

    /** Nothing left to show is the state the Family screen draws as "Get started": start a family, or join one. */
    @Test fun deletingTheOnlyFamilyLeavesNoneShown() = runTest {
        var families = listOf(snapshot(family = "family-a").family)
        val (model, repository) = model(object : TestParentApi() {
            override suspend fun listFamilies() = families
            override suspend fun leaveFamily(familyId: String): LeaveOutcome { families = emptyList(); return LeaveOutcome.DELETED }
        })
        model.load(identity)
        assertEquals(LeaveOutcome.DELETED, model.leaveFamily(identity, "family-a"))
        assertNull(repository.state.value.snapshot)
        assertTrue(model.state.value.families.isEmpty())
    }

    /** The server kept the family (last parent, children in it): nothing moves, and it is read again so the screen is current. */
    @Test fun aFamilyTheServerKeepsStaysShown() = runTest {
        var reads = 0
        val (model, repository) = model(object : TestParentApi() {
            override suspend fun leaveFamily(familyId: String) = LeaveOutcome.KEPT
            override suspend fun readFamily(familyId: String) = super.readFamily(familyId).also { reads++ }
        })
        model.load(identity)
        val before = reads
        assertEquals(LeaveOutcome.KEPT, model.leaveFamily(identity, "family-a"))
        assertEquals("family-a", repository.state.value.snapshot?.family?.id)
        assertEquals(listOf("family-a"), model.state.value.families.map { it.id })
        assertEquals(before + 1, reads)
    }

    @Test fun confirmedFamilyCreationClosesCreateFlowWhenFollowupReadFails() = runTest {
        var creates = 0
        val api = object : TestParentApi() {
            override suspend fun createFamily(name: String, key: String): CreatedFamily { creates++; return super.createFamily(name, key) }
            override suspend fun listFamilies(): List<FamilyV1> = error("read offline after commit")
        }
        val dao = MemoryFamilyDao(); val encrypted = store()
        val model = FamilyViewModel(api, FamilyRepository(api, dao) { identity }, PendingChildCreation(api, dao, { identity }), PairingModel(api, { identity }), encrypted, { identity })
        assertTrue(runCatching { model.createFamily(identity, "Family") }.isSuccess)
        assertEquals("Family created. Refresh to load the latest view.", model.state.value.message)
        assertNull(encrypted.read("pending-family:${identity.userId}"))
        assertTrue(runCatching { model.refresh(identity) }.isSuccess)
        assertEquals(1, creates)
    }

    @Test fun confirmedChildCreationClosesCreateFlowWhenFollowupReadFails() = runTest {
        var offline = false; var creates = 0
        val api = object : TestParentApi() {
            override suspend fun createChild(request: CreateChildRequest): ChildV1 { creates++; offline = true; return super.createChild(request) }
            override suspend fun readFamily(familyId: String) = if (offline) error("read offline after commit") else super.readFamily(familyId)
        }
        val dao = MemoryFamilyDao()
        val model = FamilyViewModel(api, FamilyRepository(api, dao) { identity }, PendingChildCreation(api, dao, { identity }), PairingModel(api, { identity }), store(), { identity })
        model.load(identity)
        assertTrue(runCatching { model.submitChild(identity, "Child") }.isSuccess)
        assertEquals("Child created. Refresh to load the latest view.", model.state.value.message)
        offline = false
        model.refresh(identity)
        assertEquals(1, creates)
        assertNull(dao.getPending(identity.userId, "family-a"))
    }

    @Test fun familyCreationTimeoutReusesKeyAcrossControllerRecreation() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val api = object : TestParentApi() {
            override suspend fun createFamily(name: String, key: String): CreatedFamily {
                requests += name to key
                if (requests.size == 1) error("response lost")
                return super.createFamily(name, key)
            }
        }
        val dao = MemoryFamilyDao()
        val store = store()
        fun controller(key: String) = FamilyViewModel(api, FamilyRepository(api, dao) { identity },
            PendingChildCreation(api, dao, { identity }), PairingModel(api, { identity }), store, { identity }) { key }
        assertTrue(runCatching { controller("stable").createFamily(identity, " Family ") }.isFailure)
        controller("different").createFamily(identity, "Family")
        assertEquals(listOf("Family" to "stable", "Family" to "stable"), requests)
    }

    @Test fun savedViewShowsBeforeTheNetworkAnswerAndIsReadOnlyUntilItArrives() = runTest {
        val release = CompletableDeferred<Unit>()
        var slow = false
        val api = object : TestParentApi() {
            override suspend fun listFamilies(): List<FamilyV1> { if (slow) release.await(); return super.listFamilies() }
        }
        val dao = MemoryFamilyDao()
        val repository = FamilyRepository(api, dao) { identity }
        val controller = FamilyViewModel(api, repository, PendingChildCreation(api, dao, { identity }), PairingModel(api, { identity }), store(), { identity })
        controller.load(identity)
        controller.hideVisible()
        assertNull(repository.state.value.snapshot)
        slow = true
        val loading = async { runCatching { controller.load(identity) } }
        runCurrent()
        val early = repository.state.value
        assertNotNull(early.snapshot)
        assertTrue(early.cached)
        assertTrue(early.loading)
        assertNull(early.failure)
        release.complete(Unit)
        assertTrue(loading.await().isSuccess)
        assertFalse(repository.state.value.cached)
        assertFalse(repository.state.value.loading)
    }

    @Test fun emptyFamilyReadHasHonestEmptyState() = runTest {
        val api = object : TestParentApi() { override suspend fun listFamilies() = emptyList<FamilyV1>() }
        val dao = MemoryFamilyDao()
        val repository = FamilyRepository(api, dao) { identity }
        val controller = FamilyViewModel(api, repository, PendingChildCreation(api, dao, { identity }), PairingModel(api, { identity }), store(), { identity })
        controller.load(identity)
        assertTrue(controller.state.value.families.isEmpty())
        assertNull(repository.state.value.snapshot)
        assertFalse(controller.state.value.busy)
    }

    @Test fun immediateHideRefusesLateFamilyAndPairingCompletion() = runTest {
        val release = CompletableDeferred<Unit>()
        val api = object : TestParentApi() {
            override suspend fun listFamilies(): List<FamilyV1> { release.await(); return super.listFamilies() }
            override suspend fun createPairing(childId: String): PairingCode { release.await(); return PairingCode("123456", "2099-01-01T00:00:00Z") }
        }
        val dao = MemoryFamilyDao()
        val repository = FamilyRepository(api, dao) { identity }
        val pairing = PairingModel(api, { identity })
        val controller = FamilyViewModel(api, repository, PendingChildCreation(api, dao, { identity }), pairing, store(), { identity })
        val loading = async { runCatching { controller.load(identity) } }
        val issuing = async { runCatching { pairing.issue(identity, "child") } }
        runCurrent()
        controller.hideVisible()
        assertFalse(controller.state.value.busy)
        assertNull(pairing.state.value.code)
        release.complete(Unit)
        assertTrue(loading.await().isFailure)
        assertTrue(issuing.await().isFailure)
        assertTrue(controller.state.value.families.isEmpty())
        assertNull(repository.state.value.snapshot)
        assertNull(pairing.state.value.code)
    }
}
