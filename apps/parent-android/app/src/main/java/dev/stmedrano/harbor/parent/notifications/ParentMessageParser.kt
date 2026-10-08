package dev.stmedrano.harbor.parent.notifications

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyState

@Serializable data class ParentRoute(val version: Int, val kind: String, val familyId: String? = null,
    val childId: String? = null, val deviceId: String? = null, val resourceId: String? = null)
data class ParentHint(val registrationId: String, val route: ParentRoute)

fun ParentHint.matchesFamily(identity: ParentIdentity, state: FamilyState): Boolean {
    val snapshot = state.snapshot ?: return false
    return !state.cached && !state.loading && state.failure == null &&
        snapshot.family.id == route.familyId && snapshot.membership.userId == identity.userId &&
        snapshot.membership.status == "active" && snapshot.membership.role in setOf("owner", "parent") &&
        snapshot.children.any { it.id == route.childId } &&
        snapshot.devices.any { it.id == route.deviceId && it.childId == route.childId && it.status == "active" }
}

object ParentMessageParser {
    private val fields = setOf("version", "kind", "familyId", "childId", "deviceId", "resourceId")
    internal fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
    fun parse(data: Map<String, String>): ParentHint? = runCatching {
        require(data.keys == setOf("route", "parentRegistrationId"))
        val registration = data.getValue("parentRegistrationId")
        require(uuid(registration))
        ParentHint(registration, checkNotNull(parseRoute(data.getValue("route"))))
    }.getOrNull()
    fun parseRoute(encoded: String): ParentRoute? = runCatching {
        require(encoded.length <= 4096)
        val route = Json.parseToJsonElement(encoded).jsonObject
        require(route.keys == fields)
        val version = route.getValue("version").jsonPrimitive
        require(!version.isString && version.intOrNull == 1)
        val kind = route.getValue("kind").jsonPrimitive
        require(kind.isString && kind.content in setOf("device.state.changed", "device.command.created"))
        for (field in fields - setOf("version", "kind")) route.getValue(field).let {
            require(it.jsonPrimitive.isString && uuid(it.jsonPrimitive.content))
        }
        Json.decodeFromJsonElement<ParentRoute>(route)
    }.getOrNull()
}
