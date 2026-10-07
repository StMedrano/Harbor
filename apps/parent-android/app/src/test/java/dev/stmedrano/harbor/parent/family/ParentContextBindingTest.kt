package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.ParentContextBinding
import dev.stmedrano.harbor.parent.auth.*
import dev.stmedrano.harbor.parent.notifications.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ParentContextBindingTest {
    private class Values : AuthValues {
        val map = mutableMapOf<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
        override fun clear() = map.clear()
    }
    @Test fun coldBindingPreservesOwnLostResponseKeyCacheAndFailedOptInRetry() = runTest {
        val owner = ParentIdentity("parent-a", "session-a")
        val dao = MemoryFamilyDao(); val values = Values()
        val cipher = object : AuthCipher {
            override fun encrypt(slot: String, value: ByteArray) = value
            override fun decrypt(slot: String, value: ByteArray) = value
        }
        val secure = SecureAuthStore(values, cipher)
        var offline = false
        val requests = mutableListOf<CreateChildRequest>()
        val api = object : TestParentApi() {
            override suspend fun listFamilies(): List<FamilyV1> { if (offline) error("offline"); return super.listFamilies() }
            override suspend fun createChild(request: CreateChildRequest): ChildV1 { requests += request; if (requests.size == 1) error("response lost after commit"); return super.createChild(request) }
        }
        fun model() = FamilyViewModel(api, FamilyRepository(api, dao) { owner }, PendingChildCreation(api, dao, { owner }), PairingModel(api, { owner }), secure, { owner })
        val first = model(); first.load(owner)
        runCatching { first.submitChild(owner, "Child") }
        val key = checkNotNull(dao.getPending(owner.userId, "family-a")).idempotencyKey
        val foreign = snapshot(user = "parent-b")
        dao.putSnapshot(dev.stmedrano.harbor.parent.data.FamilyCacheRow("parent-b", "family-a", kotlinx.serialization.json.Json.encodeToString(foreign), 1234))
        val registration = ParentRegistrationStore(Values())
        registration.setOptedIn(owner, true)
        registration.confirm(owner, REGISTRATION)
        offline = true
        val restarted = model(); var attempts = 0
        val binding = ParentContextBinding({ owner }, restarted, registration,
            { registration.clearMarker(); restarted.hideVisible() }, { attempts++ })
        assertTrue(runCatching { binding.ensure() }.isFailure)
        assertTrue(restarted.repository.state.value.cached)
        assertEquals(key, dao.getPending(owner.userId, "family-a")?.idempotencyKey)
        assertTrue(registration.optedIn(owner))
        assertNull(registration.marker(owner))
        assertNull(dao.getSnapshot("parent-b", "family-a"))
        offline = false
        binding.ensure()
        assertEquals(1, attempts)
        restarted.submitChild(owner, "Child")
        assertEquals(requests[0], requests[1])
        assertNull(dao.getPending(owner.userId, "family-a"))
        binding.ensure()
        assertEquals(1, attempts)
    }
}
