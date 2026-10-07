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
