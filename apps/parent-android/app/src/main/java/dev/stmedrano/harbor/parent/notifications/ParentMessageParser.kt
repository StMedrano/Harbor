package dev.stmedrano.harbor.parent.notifications

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable data class ParentRoute(val version: Int, val kind: String, val familyId: String? = null,
    val childId: String? = null, val deviceId: String? = null, val resourceId: String? = null)
data class ParentHint(val registrationId: String, val route: ParentRoute)

object ParentMessageParser {
    private val fields = setOf("version", "kind", "familyId", "childId", "deviceId", "resourceId")
    internal fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    fun parse(data: Map<String, String>): ParentHint? = runCatching {
        require(data.keys == setOf("route", "parentRegistrationId"))
        val registration = data.getValue("parentRegistrationId")
        require(uuid(registration))
        val encoded = data.getValue("route")
        require(encoded.length <= 4096)
        val route = Json.parseToJsonElement(encoded).jsonObject
        require(fields.containsAll(route.keys))
        val version = route.getValue("version").jsonPrimitive
        require(!version.isString && version.intOrNull == 1)
        val kind = route.getValue("kind").jsonPrimitive
        require(kind.isString && kind.content.isNotBlank() && kind.content.length <= 128 && kind.content.none(Char::isISOControl))
        for (field in fields - setOf("version", "kind")) route[field]?.let {
            require(it.jsonPrimitive.isString && uuid(it.jsonPrimitive.content))
        }
        ParentHint(registration, Json.decodeFromJsonElement<ParentRoute>(route))
    }.getOrNull()
}
