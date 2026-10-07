package dev.stmedrano.harbor.parent.notifications

import org.junit.Assert.*
import org.junit.Test

internal const val FAMILY = "11111111-1111-4111-8111-111111111111"
internal const val REGISTRATION = "22222222-2222-4222-8222-222222222222"
internal const val EVENT = "33333333-3333-4333-8333-333333333333"
internal const val DEVICE = "44444444-4444-4444-8444-444444444444"
internal fun envelope(route: String = """{"version":1,"kind":"device.state.changed","familyId":"$FAMILY","resourceId":"$EVENT","deviceId":"$DEVICE"}""") =
    mapOf("route" to route, "parentRegistrationId" to REGISTRATION)

class ParentMessageParserTest {
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
