package dev.stmedrano.harbor.acceptance

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class ReceiptStoreTest {
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
