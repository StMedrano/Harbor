package dev.stmedrano.harbor.parent.child

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChildUsageFailureTest {
    private val binding = ChildBinding("22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
    private val session = ChildAuthSession(ChildCredentials("child-access", "child-refresh", 5000), "11111111-1111-4111-8111-111111111111", true)
    private val signer = object : ChildSigner {
        override fun exists() = true
        override fun publicKeySpki() = "public-spki"
        override fun sign(bytes: ByteArray) = ByteArray(64) { 1 }
        override fun delete() {}
    }
    private fun api(status: Int, body: String) = ChildApi("https://parent-ci.invalid", "sb_publishable_fixture", { ChildReply(status, body) }, { 1000L }, { "nonce" }, signer)

    @Test fun applicationRejectionsAreNotAuthenticationFailures() = runTest {
        for ((status, code) in listOf(400 to "VALIDATION_FAILED", 404 to "NOT_FOUND", 409 to "STALE_VERSION", 409 to "IDEMPOTENCY_CONFLICT", 413 to "VALIDATION_FAILED")) {
            try { api(status, "{\"code\":\"$code\"}").signedUsage("report-device-usage", "{}", binding, session); fail("$status accepted") }
            catch (rejected: UsageRequestRejected) { assertEquals(status, rejected.status); assertEquals(code, rejected.code) }
        }
    }

    @Test fun authorizationFailuresKeepTheExistingBlockingPath() = runTest {
        for ((status, code, expected) in listOf(Triple(401, "AUTH_REQUIRED", "REQUEST_FAILED"), Triple(403, "FORBIDDEN", "REQUEST_FAILED"), Triple(403, "DEVICE_REVOKED", "DEVICE_REVOKED"))) {
            try { api(status, "{\"code\":\"$code\"}").signedUsage("clear-device-usage", "{}", binding, session); fail("$status accepted") }
            catch (denied: ChildRequestDenied) { assertEquals(status, denied.status); assertEquals(expected, denied.code) }
        }
    }

    @Test fun overloadAndServerErrorsStayConnectionProblems() = runTest {
        for (status in listOf(429, 500, 503)) {
            try { api(status, "").signedUsage("get-device-usage-checkpoint", "{\"version\":1}", binding, session); fail("$status accepted") }
            catch (_: ChildConnectionUnavailable) {}
        }
    }
}
