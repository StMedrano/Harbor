package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.*
import io.github.jan.supabase.realtime.realtime
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class FamilyRealtimeSdkTest {
    @Test fun PinnedSdkPrivateChannelAndOldCloseCannotRemoveNewSameTopicBinding() = runTest {
        var requests = 0
        val store = SecureAuthStore(object : AuthValues {
            val map = mutableMapOf<String, String>()
            override fun read(key: String) = map[key]
            override fun write(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
            override fun clear() = map.clear()
        }, object : AuthCipher {
            override fun encrypt(slot: String, value: ByteArray) = value
            override fun decrypt(slot: String, value: ByteArray) = value
        })
        val client = SupabaseAuthGateway.createClient("https://parent.test", "sb_publishable_fixture", store,
            MockEngine { requests++; error("No live or simulated connection expected in configuration proof") })
        try {
            val auth = ParentAuthRepository(SupabaseAuthGateway(client, store), store)
            val factory = SdkFamilyChannelFactory(client, auth, backgroundScope)
            val first = factory.create(FAMILY)
            val sdkFirst = client.realtime.subscriptions.values.single()
            assertEquals("realtime:family:$FAMILY", sdkFirst.topic)
            // 3.8.0 exposes no public private-channel getter. Inspect the exact
            // pinned configuration field; this is not a live connection claim.
            val privateField = sdkFirst.javaClass.getDeclaredField("isPrivate").also { it.isAccessible = true }
            assertTrue(privateField.getBoolean(sdkFirst))
            first.close()
            assertTrue(client.realtime.subscriptions.isEmpty())
            val second = factory.create(FAMILY)
            val sdkSecond = client.realtime.subscriptions.values.single()
            assertNotSame(sdkFirst, sdkSecond)
            first.close()
            assertSame(sdkSecond, client.realtime.subscriptions.values.single())
            assertTrue(runCatching { second.join() }.isFailure)
            assertEquals(0, requests)
            second.close()
        } finally { client.close() }
    }
}
