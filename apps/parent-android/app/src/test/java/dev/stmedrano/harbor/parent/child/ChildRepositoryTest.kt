package dev.stmedrano.harbor.parent.child

import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChildRepositoryTest {
    private val user = "11111111-1111-4111-8111-111111111111"
    private val confirmed = ChildBinding("22222222-2222-4222-8222-222222222222",
        "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private fun session(anonymous: Boolean = true, id: String = user, expires: Long = 5000) =
        ChildAuthSession(ChildCredentials("child-access", "child-refresh", expires), id, anonymous)
    private class Store : ChildStore {
        var record: ChildRecord? = null
        var readFails = false
        var writeFails = false
        var history = false
        override var claimPending = false
        override val hasHistory get() = history || claimPending || record != null
        override fun load(): ChildRecord? { if (readFails) throw IOException("fixture read failure"); return record }
        override fun save(record: ChildRecord) { if (writeFails) throw IOException("fixture write failure"); this.record = record }
        override fun clear() { record = null }
    }
    private class Key : ChildSigner {
        override fun exists() = true
        override fun publicKeySpki() = "child-public-spki"
        override fun sign(bytes: ByteArray) = ByteArray(64) { 1 }
        override fun delete() {}
    }
    private inner class Backend(private val store: Store) : ChildBackend {
        var auth = session()
        var authCalls = 0
        var failAuth = false
        var claims = 0
        var signedOwner: String? = null
        var failClaim = false
        var refreshed = session()
        var refreshFails = false
        var revoked = false
        var fcmCalls = 0
        var fcmSession: ChildAuthSession? = null
        override suspend fun anonymousSignup(): ChildAuthSession {
            authCalls++
            if (failAuth) throw IOException("fixture lost Auth reply")
            return auth
        }
        override suspend fun refresh(session: ChildAuthSession): ChildAuthSession {
            if (refreshFails) throw IOException("fixture connection unavailable")
            return refreshed
        }
        override suspend fun claim(code: String, publicKeySpki: String, session: ChildAuthSession): ChildBinding {
            assertEquals("123456", code)
            assertEquals("child-public-spki", publicKeySpki)
            assertTrue(store.claimPending)
            assertEquals(session, store.record?.session)
            assertNull(store.record?.binding)
            claims++
            if (failClaim) throw IOException("simulated lost reply")
            return confirmed
        }
        override suspend fun sync(binding: ChildBinding, session: ChildAuthSession): Long {
            assertEquals(confirmed, binding)
            if (revoked) throw ChildRequestDenied(403, "DEVICE_REVOKED")
            signedOwner = session.userId
            return 2
        }
        override suspend fun registerFcm(binding: ChildBinding, token: String, session: ChildAuthSession) {
            fcmCalls++
            assertEquals(confirmed, binding)
            assertEquals("ephemeral-fcm", token)
            assertTrue(session.anonymous)
            if (revoked) throw ChildRequestDenied(403, "DEVICE_REVOKED")
            fcmSession = session
        }
    }
    private fun repository(store: Store, backend: ChildBackend) = ChildRepository(backend, store, Key(), { 1000L })

    @Test fun parentSessionRejected() = runTest {
        val store = Store(); val backend = Backend(store).apply { auth = session(false) }
        val value = repository(store, backend)
        assertEquals(PairResult.Rejected, value.pair("123456"))
        assertNull(store.record); assertNull(value.binding.value); assertEquals(0, backend.claims)
    }
    @Test fun claimConfirmsBeforePersist() = runTest {
        val store = Store(); val backend = Backend(store); val value = repository(store, backend)
        assertEquals(PairResult.Confirmed(confirmed), value.pair("123456"))
        assertEquals(confirmed, store.record?.binding)
        assertEquals(confirmed, value.binding.value)
        assertFalse(store.claimPending)
    }
    @Test fun invalidCodeKeepsSetup() = runTest {
        val store = Store(); val backend = Backend(store); val value = repository(store, backend)
        assertEquals(PairResult.Rejected, value.pair("12345"))
        assertNull(store.record); assertFalse(store.claimPending); assertEquals(0, backend.claims)
    }
    @Test fun lostResponseDoesNotReclaimEvenAfterReopen() = runTest {
        val store = Store(); val backend = Backend(store).apply { failClaim = true }
        val value = repository(store, backend)
        assertEquals(PairResult.UnknownOutcome, value.pair("123456"))
        assertNull(value.binding.value); assertTrue(store.claimPending)
        val reopened = repository(store, backend); reopened.restore()
        assertEquals(PairResult.UnknownOutcome, reopened.pair("123456"))
        assertEquals(1, backend.claims); assertNull(reopened.binding.value)
    }
    @Test fun existingBindingSurvivesFailure() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val backend = Backend(store); val value = repository(store, backend); value.restore()
        assertEquals(PairResult.Rejected, value.pair("12345"))
        assertEquals(confirmed, value.binding.value); assertEquals(confirmed, store.record?.binding)
        assertEquals(0, backend.claims)
    }
    @Test fun signedSyncUsesOnlyChildOwner() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val backend = Backend(store); val value = repository(store, backend); value.restore()
        assertEquals(ChildSyncState.Fresh(1000L, 2L), value.sync())
        assertEquals(user, backend.signedOwner)
    }
    @Test fun refreshKeepsAnonymousOwner() = runTest {
        val store = Store().apply { record = ChildRecord(session(expires = 999), confirmed) }
        val backend = Backend(store).apply { refreshed = session(id = "55555555-5555-4555-8555-555555555555") }
        val value = repository(store, backend); value.restore()
        assertTrue(value.state.value is ChildSyncState.Blocked)
        assertNull(value.binding.value)
        assertEquals(user, store.record?.session?.userId)
    }
    @Test fun localRemovalRequiresConfirmedRevocation() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val value = repository(store, Backend(store)); value.restore()
        try { value.clearAfterConfirmedRevocation(); fail("Removal must require confirmation") }
        catch (_: IllegalStateException) {}
        assertEquals(confirmed, store.record?.binding)
    }
    @Test fun interruptedAnonymousSignupDoesNotCreateAnotherIdentity() = runTest {
        val store = Store(); val backend = Backend(store).apply { failAuth = true }
        val value = repository(store, backend)
        assertEquals(PairResult.UnknownOutcome, value.pair("123456"))
        assertEquals(PairResult.UnknownOutcome, repository(store, backend).pair("123456"))
        assertEquals(1, backend.authCalls); assertTrue(store.claimPending)
    }
    @Test fun readFailureKeepsProfileBlocked() = runTest {
        val store = Store().apply { readFails = true }; val backend = Backend(store)
        val value = repository(store, backend)
        assertEquals(PairResult.Rejected, value.pair("123456"))
        assertEquals(ChildSyncState.Blocked(ChildFailure.STORAGE_UNAVAILABLE), value.state.value)
        assertEquals(0, backend.authCalls)
    }
    @Test fun revokedReplyHidesStateEvenIfPersistenceFails() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val backend = Backend(store).apply { revoked = true }
        val value = repository(store, backend); value.restore(); store.writeFails = true
        assertEquals(ChildSyncState.Blocked(ChildFailure.REVOKED), value.sync())
        assertEquals(ChildSyncState.Blocked(ChildFailure.REVOKED), value.state.value)
    }
    @Test fun missingEncryptedRecordWithHistoryIsBlocked() = runTest {
        val store = Store().apply { history = true }
        val value = repository(store, Backend(store)); value.restore()
        assertEquals(ChildSyncState.Blocked(ChildFailure.STORAGE_UNAVAILABLE), value.state.value)
        assertNull(value.binding.value)
    }
    @Test fun fcmRegistrationRequiresExactConfirmedBinding() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val backend = Backend(store); val value = repository(store, backend); value.restore()
        assertFalse(value.registerFcm(confirmed.copy(familyId = user), "ephemeral-fcm"))
        assertEquals(0, backend.fcmCalls)
        assertTrue(value.registerFcm(confirmed, "ephemeral-fcm"))
        assertEquals(1, backend.fcmCalls)
        assertEquals(user, backend.fcmSession?.userId)
    }
    @Test fun fcmRegistrationDoesNotInventSyncOrReceiptEvidence() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val backend = Backend(store); val value = repository(store, backend); value.restore()
        assertTrue(value.registerFcm(confirmed, "ephemeral-fcm"))
        assertEquals(ChildSyncState.Stale(null), value.state.value)
        assertNull(store.record?.lastSuccessAt)
        assertNull(store.record?.desiredVersion)
        assertEquals(session(), store.record?.session)
    }
    @Test fun fcmRegistrationRefreshesOnlyItsAnonymousOwner() = runTest {
        var now = 1000L
        val store = Store().apply { record = ChildRecord(session(expires = 1500), confirmed) }
        val backend = Backend(store).apply { refreshed = session().copy(credentials = ChildCredentials("fresh-access", "fresh-refresh", 5000)) }
        val value = ChildRepository(backend, store, Key(), { now }); value.restore()
        now = 1500
        assertTrue(value.registerFcm(confirmed, "ephemeral-fcm"))
        assertEquals(backend.refreshed, backend.fcmSession)
        assertEquals(user, backend.fcmSession?.userId)
    }
    @Test fun knownFcmRevocationBlocksImmediatelyEvenIfStorageFails() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val backend = Backend(store).apply { revoked = true }
        val value = repository(store, backend); value.restore(); store.writeFails = true
        assertFalse(value.registerFcm(confirmed, "ephemeral-fcm"))
        assertEquals(ChildSyncState.Blocked(ChildFailure.REVOKED), value.state.value)
        assertFalse(value.registerFcm(confirmed, "ephemeral-fcm"))
        assertEquals(1, backend.fcmCalls)
    }
    @Test fun oldHintCannotReadAReplacedBinding() = runTest {
        val store = Store().apply { record = ChildRecord(session(), confirmed) }
        val backend = Backend(store); val value = repository(store, backend); value.restore()
        assertEquals(ChildSyncState.Stale(null), value.sync(confirmed.copy(deviceId = user)))
        assertNull(backend.signedOwner)
        assertEquals(ChildSyncState.Fresh(1000, 2), value.sync(confirmed))
        assertEquals(user, backend.signedOwner)
    }
    @Test fun usageCapturesOnlyCurrentAnonymousSessionAndRejectsForeignBinding()=runTest {
        val store=Store().apply{record=ChildRecord(session(),confirmed)}
        val backend=Backend(store);val value=repository(store,backend);value.restore()
        var calls=0
        val seen=value.withCurrentSession(confirmed){calls++;it.credentials.accessToken}
        assertEquals("child-access",seen)
        try {value.withCurrentSession(confirmed.copy(deviceId=user)){calls++;"wrong"};fail("foreign usage binding allowed")}
        catch(_:IllegalStateException){}
        assertEquals(1,calls)
    }
    @Test fun usageRevokeReplyImmediatelyBlocksFurtherSessionAccess()=runTest {
        val store=Store().apply{record=ChildRecord(session(),confirmed)}
        val value=repository(store,Backend(store));value.restore()
        try {value.withCurrentSession(confirmed){throw ChildRequestDenied(403,"DEVICE_REVOKED")};fail("revoked response hidden")}
        catch(_:ChildRequestDenied){}
        assertEquals(ChildSyncState.Blocked(ChildFailure.REVOKED),value.state.value)
        var calls=0
        try {value.withCurrentSession(confirmed){calls++;true};fail("revoked session accessible")}
        catch(_:IllegalStateException){}
        assertEquals(0,calls)
    }
    @Test fun refreshOutagePreservesCredentialsAndReopensAfterReconnect() = runTest {
        val store = Store(); val original = ChildRecord(session(expires = 999), confirmed)
        store.record = original
        val backend = Backend(store).apply { refreshFails = true }
        val value = repository(store, backend)
        value.restore()
        assertEquals(ChildSyncState.Blocked(ChildFailure.NETWORK_UNAVAILABLE), value.state.value)
        assertEquals(original, store.record); assertNull(value.binding.value)
        backend.refreshFails = false; value.restore()
        assertEquals(confirmed, value.binding.value)
        assertEquals(user, store.record?.session?.userId)
    }}
