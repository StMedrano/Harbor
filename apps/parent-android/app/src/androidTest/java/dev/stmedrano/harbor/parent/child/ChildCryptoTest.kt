package dev.stmedrano.harbor.parent.child

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.auth.EncryptedSessionManager
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyStore
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class ChildCryptoTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val owner = "11111111-1111-4111-8111-111111111111"
    private val binding = ChildBinding("22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private val session = ChildAuthSession(ChildCredentials("synthetic-child-access", "synthetic-child-refresh", 5000), owner, true)
    private class Backend(private val refreshed: ChildAuthSession) : ChildBackend {
        override suspend fun anonymousSignup(): ChildAuthSession = error("No live Auth in fixture")
        override suspend fun refresh(session: ChildAuthSession) = refreshed
        override suspend fun claim(code: String, publicKeySpki: String, session: ChildAuthSession): ChildBinding = error("No live claim in fixture")
        override suspend fun sync(binding: ChildBinding, session: ChildAuthSession) = 2L
        override suspend fun registerFcm(binding: ChildBinding, token: String, session: ChildAuthSession) = error("No live provider in fixture")
    }
    @Test fun encryptedChildReopenAndRefreshPreserveParentCredentials() = runBlocking {
        val name = "child-crypto-reopen-test"
        val childStore = EncryptedChildStore(context, name)
        val childKey = ChildDeviceKey("child-crypto-reopen-key")
        val parent = SecureAuthStore.open(context)
        try {
            childStore.clear(); childKey.publicKeySpki(); parent.clear()
            EncryptedSessionManager(parent).saveSession(UserSession("synthetic-parent-access", "synthetic-parent-refresh", expiresIn = 3600, tokenType = "bearer"))
            childStore.save(ChildRecord(session.copy(credentials = session.credentials.copy(expiresAt = 999)), binding))
            val next = session.copy(credentials = ChildCredentials("refreshed-child-access", "refreshed-child-refresh", 15000))
            val reopened = EncryptedChildStore(context, name)
            val repo = ChildRepository(Backend(next), reopened, childKey, { 1000L }); repo.restore()
            assertEquals(binding, repo.binding.value)
            assertEquals("refreshed-child-refresh", EncryptedChildStore(context, name).load()?.session?.credentials?.refreshToken)
            assertEquals("synthetic-parent-refresh", EncryptedSessionManager(SecureAuthStore.open(context)).loadSession().refreshToken)
            val plaintext = context.getSharedPreferences(name, Context.MODE_PRIVATE).all.values.joinToString()
            assertFalse(plaintext.contains("child-access")); assertFalse(plaintext.contains("child-refresh")); assertFalse(plaintext.contains(owner))
            val aes = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(childStore.keyAlias, null)
            assertNotNull(aes); assertNull(aes.encoded)
            childStore.clear()
            assertEquals("synthetic-parent-refresh", EncryptedSessionManager(parent).loadSession().refreshToken)
        } finally { childStore.clear(); childKey.delete(); parent.clear() }
    }
    @Test fun nonExportableChildP256ProofVerifiesAfterKeyReopen() {
        val alias = "child-p256-proof-test"
        val key = ChildDeviceKey(alias)
        try {
            key.delete()
            val encoded = key.publicKeySpki()
            val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded))) as ECPublicKey
            assertEquals(256, publicKey.params.order.bitLength())
            val message = "child exact wire proof".toByteArray()
            val raw = ChildDeviceKey(alias).sign(message)
            assertEquals(64, raw.size)
            fun scalar(bytes: ByteArray) = BigInteger(1, bytes).toByteArray()
            val r = scalar(raw.copyOfRange(0, 32)); val s = scalar(raw.copyOfRange(32, 64))
            val der = byteArrayOf(0x30, (4 + r.size + s.size).toByte(), 2, r.size.toByte()) + r + byteArrayOf(2, s.size.toByte()) + s
            val verifier = Signature.getInstance("SHA256withECDSA").apply { initVerify(publicKey); update(message) }
            assertTrue(verifier.verify(der))
            val privateKey = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null)
            assertNull(privateKey.encoded)
        } finally { key.delete() }
    }
    @Test fun lostChildEncryptionKeyStaysBlockedAcrossReopen() = runBlocking {
        val name = "child-key-loss-test"; val store = EncryptedChildStore(context, name)
        val key = ChildDeviceKey("child-key-loss-p256")
        try {
            store.clear(); key.publicKeySpki(); store.save(ChildRecord(session, binding))
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(store.keyAlias) }
            val first = ChildRepository(Backend(session), store, key, { 1000L }); first.restore()
            assertTrue(first.state.value is ChildSyncState.Blocked); assertNull(first.binding.value)
            val reopened = EncryptedChildStore(context, name)
            assertTrue(reopened.hasHistory)
            val next = ChildRepository(Backend(session), reopened, key, { 1000L }); next.restore()
            assertTrue(next.state.value is ChildSyncState.Blocked); assertNull(next.binding.value)
        } finally { store.clear(); key.delete() }
    }
    @Test fun interruptedEnrollmentMarkerSurvivesProcessReopen() = runBlocking {
        val name = "child-pending-marker-test"; val store = EncryptedChildStore(context, name)
        val key = ChildDeviceKey("child-pending-marker-key")
        try {
            store.clear(); store.claimPending = true
            val reopened = EncryptedChildStore(context, name)
            val repo = ChildRepository(Backend(session), reopened, key, { 1000L }); repo.restore()
            assertEquals(ChildSyncState.Blocked(ChildFailure.UNKNOWN_OUTCOME), repo.state.value)
            assertEquals(PairResult.UnknownOutcome, repo.pair("123456"))
        } finally { store.clear(); key.delete() }
    }
}
