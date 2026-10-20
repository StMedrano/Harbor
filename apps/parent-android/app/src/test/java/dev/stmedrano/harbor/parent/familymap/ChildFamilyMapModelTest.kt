package dev.stmedrano.harbor.parent.familymap

import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChildFamilyMapModelTest {
    private val lease = ProfileLease(ProfileRole.CHILD, "22222222-2222-4222-8222-222222222222", 1)
    private val raw = checkNotNull(javaClass.classLoader?.getResource("family-map-reply-v1.json")).readText()
    private var leaseCurrent = true
    private var fail = false
    private var reply = raw
    private val sent = mutableListOf<Pair<String, String>>()
    private val model = ChildFamilyMapModel({ _, op, body ->
        sent += op to body
        if (fail) throw java.io.IOException("offline")
        reply
    }, { leaseCurrent }, { 1234L })

    @Test fun nothingIsClaimedBeforeTheFirstAnswer() = runTest {
        assertFalse(model.state.value.loaded)
        model.refresh(lease)
        assertTrue(model.state.value.loaded)
        assertEquals(3, model.state.value.people.size)
        assertEquals("get-family-map" to """{"version":1}""", sent.single())
        assertEquals(1234L, model.state.value.receivedAtMs)
    }
    @Test fun aFailedRefreshKeepsTheLastMapAndSaysItIsOffline() = runTest {
        model.refresh(lease)
        fail = true
        model.refresh(lease)
        assertTrue(model.state.value.offline)
        assertEquals(3, model.state.value.people.size)
        fail = false
        model.refresh(lease)
        assertFalse(model.state.value.offline)
    }
    @Test fun aFailureBeforeAnyAnswerStaysNotLoaded() = runTest {
        fail = true
        model.refresh(lease)
        assertFalse(model.state.value.loaded)
        assertTrue(model.state.value.offline)
    }
    @Test fun aParentTurningTheMapOffShowsNobody() = runTest {
        model.refresh(lease)
        reply = """{"enabled":false,"people":[]}"""
        model.refresh(lease)
        assertTrue(model.state.value.loaded)
        assertFalse(model.state.value.enabled)
        assertTrue(model.state.value.people.isEmpty())
    }
    @Test fun aMalformedAnswerIsNotShown() = runTest {
        reply = raw.replace("\"enabled\": true", "\"enabled\": false")
        model.refresh(lease)
        assertFalse(model.state.value.loaded)
    }
    @Test fun aStaleOrParentLeaseSendsNothing() = runTest {
        leaseCurrent = false
        model.refresh(lease)
        leaseCurrent = true
        model.refresh(lease.copy(role = ProfileRole.PARENT))
        assertTrue(sent.isEmpty())
    }
    @Test fun clearForgetsEverything() = runTest {
        model.refresh(lease)
        model.clear()
        assertEquals(FamilyMapUiState(), model.state.value)
    }
}
