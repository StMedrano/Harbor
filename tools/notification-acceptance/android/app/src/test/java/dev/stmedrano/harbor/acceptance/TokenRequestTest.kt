package dev.stmedrano.harbor.acceptance

import org.junit.Assert.*
import org.junit.Test

class TokenRequestTest {
    @Test fun pendingRequestReportsProgressThenSavesBeforeReportingSuccess() {
        val events = mutableListOf<String>()
        var complete: ((String) -> Unit)? = null
        requestFcmToken({ success, _ -> complete = success }, { events.add("saved") }, events::add)
        assertEquals(listOf("Getting FCM token…"), events)
        complete!!("private-device-token")
        assertEquals(listOf("Getting FCM token…", "saved", "FCM token obtained. Pair this test device, then register FCM."), events)
        assertFalse(events.any { it.contains("private-device-token") })
    }

    @Test fun providerFailureIsVisibleWithoutSavingOrClaimingSuccess() {
        val events = mutableListOf<String>()
        var saved = false
        requestFcmToken({ _, failure -> failure() }, { saved = true }, events::add)
        assertFalse(saved)
        assertEquals("FCM token unavailable. Check the matching Firebase setup and retry.", events.last())
    }

    @Test fun persistenceFailureDoesNotClaimTokenReady() {
        val events = mutableListOf<String>()
        requestFcmToken({ success, _ -> success("token") }, { throw IllegalStateException("disk") }, events::add)
        assertEquals("FCM token unavailable. Check the matching Firebase setup and retry.", events.last())
    }

    @Test fun synchronousProviderFailureIsVisible() {
        val events = mutableListOf<String>()
        requestFcmToken({ _, _ -> throw IllegalStateException("setup") }, {}, events::add)
        assertEquals("FCM token unavailable. Check the matching Firebase setup and retry.", events.last())
    }
}
