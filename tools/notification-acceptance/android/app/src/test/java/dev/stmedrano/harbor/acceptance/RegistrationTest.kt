package dev.stmedrano.harbor.acceptance

import org.junit.Assert.*
import org.junit.Test

class RegistrationTest {
    private val binding = DeviceBinding("12345678-1234-4234-8234-123456789abc", "23456789-1234-4234-8234-123456789abc", "34567890-1234-4234-8234-123456789abc")
    @Test fun tokenBeforePairingStaysPendingWithoutBackendCalls() {
        var calls = 0
        val registration = Registration({null}, {_, _ -> calls++})
        registration.onToken("pending-token")
        assertEquals(RegistrationResult.NeedsBinding, registration.registerCurrentToken())
        assertEquals(0, calls)
        assertNull(registration.confirmedToken)
    }
    @Test fun latestRotatedTokenSupersedesThePreviousRegistration() {
        val submitted = mutableListOf<String>()
        val registration = Registration({binding}, {device, token -> assertEquals(binding, device); submitted.add(token)})
        registration.onToken("first")
        assertEquals(RegistrationResult.Registered, registration.registerCurrentToken())
        registration.onToken("rotated")
        assertNull(registration.confirmedToken)
        assertEquals(RegistrationResult.Registered, registration.registerCurrentToken())
        assertEquals(listOf("first", "rotated"), submitted)
        assertEquals("rotated", registration.confirmedToken)
    }
    @Test fun backendFailureRetainsTokenForRetryWithoutReportingSuccess() {
        var fail = true
        val registration = Registration({binding}, {_, _ -> if(fail) throw IllegalStateException("Backend refused")})
        registration.onToken("current")
        assertEquals(RegistrationResult.RetryableFailure, registration.registerCurrentToken())
        assertNull(registration.confirmedToken)
        fail = false
        assertEquals(RegistrationResult.Registered, registration.registerCurrentToken())
        assertEquals("current", registration.confirmedToken)
    }
}
