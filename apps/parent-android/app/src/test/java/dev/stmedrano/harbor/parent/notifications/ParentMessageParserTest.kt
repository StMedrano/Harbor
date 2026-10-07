package dev.stmedrano.harbor.parent.notifications

import org.junit.Assert.*
import org.junit.Test
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.*

internal const val FAMILY = "11111111-1111-4111-8111-111111111111"
internal const val REGISTRATION = "22222222-2222-4222-8222-222222222222"
internal const val EVENT = "33333333-3333-4333-8333-333333333333"
internal const val DEVICE = "44444444-4444-4444-8444-444444444444"
internal const val CHILD = "55555555-5555-4555-8555-555555555555"
internal fun envelope(route: String = """{"version":1,"kind":"device.state.changed","familyId":"$FAMILY","childId":"$CHILD","resourceId":"$EVENT","deviceId":"$DEVICE"}""") =
    mapOf("route" to route, "parentRegistrationId" to REGISTRATION)

class ParentMessageParserTest {
    @Test fun referencedDeviceMustBelongToReferencedChildInFreshAuthorizedSnapshot() {
        val owner = ParentIdentity("parent-a", "session-a")
        val date = "2026-01-01T00:00:00Z"
        val base = snapshot(family = FAMILY, devices = listOf(DevicePublicV1(1, DEVICE, FAMILY, CHILD, "Phone", "standard", "active", null, date, date))).let { it.copy(
            children = it.children.map { child -> child.copy(id = CHILD) },
            devices = it.devices.map { device -> device.copy(id = DEVICE, childId = CHILD) }) }
        val hint = checkNotNull(ParentMessageParser.parse(envelope()))
        assertTrue(hint.matchesFamily(owner, FamilyState(base)))
        assertFalse(hint.matchesFamily(owner, FamilyState(base.copy(devices = base.devices.map { it.copy(childId = EVENT) }))))
        assertFalse(hint.matchesFamily(owner, FamilyState(base, cached = true)))
        assertFalse(hint.matchesFamily(owner, FamilyState(base, failure = FamilyFailure.ACCESS_DENIED)))
        assertFalse(hint.matchesFamily(ParentIdentity("other", "session"), FamilyState(base)))
    }
    @Test fun onlySupportedKindsWithEveryReferenceAreAccepted() {
        val route = envelope().getValue("route")
        assertNotNull(ParentMessageParser.parse(envelope(route.replace("device.state.changed", "device.command.created"))))
        assertNull(ParentMessageParser.parse(envelope(route.replace("device.state.changed", "future.unknown"))))
        for ((field, value) in listOf("familyId" to FAMILY, "childId" to CHILD, "deviceId" to DEVICE, "resourceId" to EVENT)) {
            assertNull("Missing $field", ParentMessageParser.parse(envelope(route.replace(",\"$field\":\"$value\"", ""))))
        }
    }
    @Test fun exactParentEnvelopePreservesReferenceWithoutContent() {
        val hint = checkNotNull(ParentMessageParser.parse(envelope()))
        assertEquals(REGISTRATION, hint.registrationId)
        assertEquals(FAMILY, hint.route.familyId)
        assertEquals(EVENT, hint.route.resourceId)
        assertEquals(DEVICE, hint.route.deviceId)
    }

    @Test fun childEnvelopeAndExtraTopLevelContentAreRejected() {
        assertNull(ParentMessageParser.parse(mapOf("route" to envelope().getValue("route"))))
        assertNull(ParentMessageParser.parse(envelope() + ("notification" to "private content")))
        assertNull(ParentMessageParser.parse(envelope() + ("token" to "private content")))
        assertNull(ParentMessageParser.parse(envelope() + ("parentRegistrationId" to "not-a-uuid")))
    }

    @Test fun UnknownSensitiveRouteFieldsAndInvalidWireTypesAreRejected() {
        val valid = envelope().getValue("route")
        for (field in listOf("childName", "location", "message", "accessToken", "unknown")) {
            assertNull(ParentMessageParser.parse(envelope(valid.dropLast(1) + ",\"$field\":\"private content\"}")))
        }
        for (route in listOf("[]", "null", "not-json", valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"version\":1", "\"version\":2"), valid.replace(EVENT, "bad-id"))) {
            assertNull(ParentMessageParser.parse(envelope(route)))
        }
    }
}
