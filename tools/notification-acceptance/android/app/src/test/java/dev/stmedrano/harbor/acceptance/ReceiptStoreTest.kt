package dev.stmedrano.harbor.acceptance

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ReceiptStoreTest {
    @Test fun stateEventIdentitySurvivesStoreRecreationAndDuplicates() {
        val route = JSONObject().put("version", 1).put("kind", "device.state.changed")
            .put("familyId", "11111111-1111-4111-8111-111111111111")
            .put("childId", "22222222-2222-4222-8222-222222222222")
            .put("deviceId", "33333333-3333-4333-8333-333333333333")
            .put("resourceId", "44444444-4444-4444-8444-444444444444")
        val saved = mutableListOf<String>()
        val store = ReceiptStore({ saved.add(it) }, { saved.toList() })
        assertTrue(store.record(route.toString(), 123L))
        assertTrue(store.record(route.toString(), 124L))
        val reloaded = ReceiptStore({ saved.add(it) }, { saved.toList() }).receipts()
        assertEquals(2, reloaded.size)
        for (text in reloaded) {
            val persisted = JSONObject(text).getJSONObject("route")
            assertEquals(6, persisted.length())
            for (key in route.keys()) assertEquals(route.get(key), persisted.get(key))
        }
        assertFalse(store.record(JSONObject(route.toString()).put("resourceId", "bad").toString(), 125L))
        assertFalse(store.record(JSONObject(route.toString()).put("accessToken", "private").toString(), 125L))
        assertEquals(2, saved.size)
    }
    private val route = "{\"version\":1,\"kind\":\"device.desired_state.changed\",\"deviceId\":\"12345678-1234-4234-8234-123456789abc\"}"
    @Test fun validRouteRecordsOnlyMinimalEvidenceAndDuplicateHints() {
        val saved = mutableListOf<String>()
        val store = ReceiptStore({saved.add(it)}, {saved.toList()})
        assertTrue(store.record(route, 123L))
        assertTrue(store.record(route, 124L))
        assertEquals(2, store.receipts().size)
        val receipt = JSONObject(saved[0])
        assertEquals(setOf("route", "receivedAt"), receipt.keys().asSequence().toSet())
        assertEquals(123L, receipt.getLong("receivedAt"))
        assertEquals("device.desired_state.changed", receipt.getJSONObject("route").getString("kind"))
    }
    @Test fun malformedVersionsIdsAndSensitiveContentNeverPersist() {
        val saved = mutableListOf<String>()
        val store = ReceiptStore({saved.add(it)}, {saved.toList()})
        val invalid = listOf("{", "null", route.replace("\"version\":1", "\"version\":2"), route.replace("\"version\":1", "\"version\":\"1\""), route.replace("12345678-1234-4234-8234-123456789abc", "bad")) +
            listOf("accessToken", "location", "message", "url").map { route.dropLast(1) + ",\"$it\":\"private\"}" }
        for (value in invalid) assertFalse(store.record(value, 123L))
        assertTrue(saved.isEmpty())
    }
}
