package dev.stmedrano.harbor.parent.child

import dev.stmedrano.harbor.parent.BuildConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChildConnectionFailureTest {
    @Test fun unavailableServiceIsRetryableWithoutParsingOrChangingOwner() = runTest {
        val key = object : ChildSigner {
            override fun exists() = true
            override fun publicKeySpki() = "unused"
            override fun sign(bytes: ByteArray) = ByteArray(64)
            override fun delete() = error("Transport failure cannot erase a key")
        }
        val api = ChildApi(BuildConfig.SUPABASE_URL, "sb_publishable_fixture", { ChildReply(503, "service unavailable") }, { 1000 }, { "unused" }, key)
        val original = ChildAuthSession(ChildCredentials("synthetic-access", "synthetic-refresh", 5000), "11111111-1111-4111-8111-111111111111", true)
        var retryable = false
        try { api.refresh(original) } catch (_: ChildConnectionUnavailable) { retryable = true }
        assertTrue(retryable)
        assertEquals("synthetic-refresh", original.credentials.refreshToken)
    }
}
