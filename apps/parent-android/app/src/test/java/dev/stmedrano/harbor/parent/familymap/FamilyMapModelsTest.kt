package dev.stmedrano.harbor.parent.familymap

import org.junit.Assert.*
import org.junit.Test

class FamilyMapModelsTest {
    private val raw = checkNotNull(javaClass.classLoader?.getResource("family-map-reply-v1.json")).readText()
    private val now = java.time.Instant.parse("2026-10-20T16:00:00Z").toEpochMilli()
    private fun rejected(text: String) { assertThrows(Exception::class.java) { decodeFamilyMap(text) } }

    @Test fun sharedFixtureDecodesWithChildrenAndASharingParent() {
        val reply = decodeFamilyMap(raw)
        assertTrue(reply.enabled)
        assertEquals(listOf("Maya", "Jo", "Alex"), reply.people.map { it.name })
        assertEquals(listOf(PersonKind.CHILD, PersonKind.CHILD, PersonKind.PARENT), reply.people.map { it.kind })
        assertTrue(reply.people[0].self)
        assertNull(reply.people[1].fix)
    }
    @Test fun aParentWithoutAPlaceIsRejected() = rejected("""{"enabled":true,"people":[{"id":"7d0f4e62-9a3b-4c85-9b27-3e5a8c0d4f96","kind":"parent","name":"Alex","self":false,"fix":null}]}""")
    @Test fun aParentCannotBeSelf() = rejected(raw.replace("\"name\": \"Alex\", \"self\": false", "\"name\": \"Alex\", \"self\": true"))
    @Test fun aTurnedOffMapCarriesNobody() {
        assertEquals(emptyList<FamilyMapPerson>(), decodeFamilyMap("""{"enabled":false,"people":[]}""").people)
        rejected(raw.replace("\"enabled\": true", "\"enabled\": false"))
    }
    @Test fun extraFieldsAndBadValuesDoNotDecode() {
        rejected(raw.replaceFirst("{", "{\"email\":\"x\","))
        rejected(raw.replace("\"Maya\"", "\"\""))
        rejected(raw.replace("41.5,", "91.0,"))
        rejected(raw.replace("5b8d2c40-7e19-4a63-9f05-1c3e6a8b2d74", "nope"))
    }
    @Test fun labelsAreTrimmedBoundedAndFreeOfControlCharacters() {
        assertTrue(validFamilyMapLabel("Alex's phone"))
        assertFalse(validFamilyMapLabel(""))
        assertFalse(validFamilyMapLabel(" Alex"))
        assertFalse(validFamilyMapLabel("Alex\n"))
        assertTrue(validFamilyMapLabel("a".repeat(40)))
        assertFalse(validFamilyMapLabel("a".repeat(41)))
    }
    @Test fun parentReportsMustBeRecentAndNotFromTheFuture() {
        val fix = FamilyMapFix("2026-10-20T15:58:00Z", 41.5, -87.6, 12)
        val report = ParentLocationReportV1(1, "11111111-1111-4111-8111-111111111111", "Alex", fix)
        assertEquals(report, validateParentLocationReport(report, now))
        assertThrows(IllegalArgumentException::class.java) { validateParentLocationReport(report.copy(fix = fix.copy(at = "2026-10-20T15:49:00Z")), now) }
        assertThrows(IllegalArgumentException::class.java) { validateParentLocationReport(report.copy(fix = fix.copy(at = "2026-10-20T16:06:00Z")), now) }
        assertThrows(IllegalArgumentException::class.java) { validateParentLocationReport(report.copy(label = " "), now) }
        assertThrows(IllegalArgumentException::class.java) { validateParentLocationReport(report.copy(version = 2), now) }
    }
    @Test fun receiptsAndSettingsAreStrict() {
        assertTrue(decodeParentLocationReceipt("""{"confirmed":true,"receivedAt":"2026-10-20T16:00:01Z"}""").confirmed)
        assertThrows(Exception::class.java) { decodeParentLocationReceipt("""{"confirmed":false,"receivedAt":"2026-10-20T16:00:01Z"}""") }
        val settings = decodeFamilyMapSettings("""{"childrenCanView":true,"mySharing":{"label":"Alex","lastAt":"2026-10-20T15:58:00Z"}}""")
        assertEquals("Alex", settings.mySharing?.label)
        assertNull(decodeFamilyMapSettings("""{"childrenCanView":false,"mySharing":null}""").mySharing)
        assertThrows(Exception::class.java) { decodeFamilyMapSettings("""{"childrenCanView":true,"mySharing":null,"x":1}""") }
    }
}
