package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.data.FamilySnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*
import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.data.ProfilePublic
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.exceptions.RestException
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import java.util.UUID
import dev.stmedrano.harbor.parent.security.MfaRequired
import io.ktor.client.request.setBody

@Serializable data class FamilyV1(val version: Int, val id: String, val name: String, val timezone: String, val createdAt: String, val updatedAt: String)
@Serializable data class FamilyMemberV1(val version: Int, val id: String, val familyId: String, val userId: String, val role: String, val status: String, val createdAt: String, val updatedAt: String)
@Serializable data class ChildV1(val version: Int, val id: String, val familyId: String, val displayName: String, val createdAt: String, val updatedAt: String)
@Serializable data class DevicePublicV1(val version: Int, val id: String, val familyId: String, val childId: String, val displayName: String, val supervisionMode: String, val status: String, val lastSeenAt: String?, val createdAt: String, val updatedAt: String)
@Serializable data class CreateChildRequest(val familyId: String, val displayName: String, val idempotencyKey: String)
@Serializable data class CreatedFamily(val familyId: String, val name: String, val role: String)
@Serializable data class PairingCode(val code: String, val expiresAt: String)

class FamilyAccessDenied : IllegalStateException("Family access is no longer available")
class PendingChildConflict : IllegalStateException("Cancel the pending child request before changing its name")

interface ParentApi {
    suspend fun listFamilies(): List<FamilyV1>
    suspend fun createFamily(name: String, key: String): CreatedFamily
    suspend fun createChild(request: CreateChildRequest): ChildV1
    suspend fun createPairing(childId: String): PairingCode
    suspend fun readFamily(familyId: String): FamilySnapshot
    suspend fun revokeDevice(familyId: String, deviceId: String)
}

class SdkParentApi(private val client: SupabaseClient, private val auth: ParentAuthRepository,
    private val now: () -> Long = System::currentTimeMillis) : ParentApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    override suspend fun listFamilies(): List<FamilyV1> = auth.withAccessToken {
        client.postgrest.from("families").select().decodeList<FamilyRow>().map { row -> row.public() }
    }

    override suspend fun createFamily(name: String, key: String): CreatedFamily = auth.withAccessToken {
        val result = client.functions.invoke("create-family", buildJsonObject { put("name", name.trim()); put("idempotencyKey", key) }, headers = jsonHeaders)
        json.decodeFromString<CreatedFamily>(result.bodyAsText())
    }
    override suspend fun createChild(request: CreateChildRequest): ChildV1 = auth.withAccessToken {
        requireUuid(request.familyId)
        json.decodeFromString<ChildV1>(client.functions.invoke("create-child", request, headers = jsonHeaders).bodyAsText())
    }
    override suspend fun createPairing(childId: String): PairingCode = auth.withAccessToken {
        requireUuid(childId)
        json.decodeFromString<PairingCode>(client.functions.invoke("create-device-pairing", buildJsonObject { put("childId", childId) }, headers = jsonHeaders).bodyAsText())
    }

    override suspend fun revokeDevice(familyId: String, deviceId: String) = auth.withAccessToken { token ->
        requireUuid(familyId); requireUuid(deviceId)
        try {
            val response = client.functions.invoke("revoke-device") {
                headers.set(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(Json.encodeToString(buildJsonObject { put("familyId", familyId); put("deviceId", deviceId) }))
            }
            check(response.status == HttpStatusCode.NoContent) { "Revocation response is unconfirmed" }
        } catch (failure: RestException) {
            val code = runCatching { Json.parseToJsonElement(failure.response.bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
            if (failure.statusCode == 403 && code == "MFA_REQUIRED") throw MfaRequired()
            throw failure
        }
    }

    override suspend fun readFamily(familyId: String): FamilySnapshot = auth.withAccessToken {
        requireUuid(familyId)
        val identity = checkNotNull(auth.identity.value)
        try {
            val member = client.postgrest.from("family_members").select {
                filter { eq("family_id", familyId); eq("user_id", identity.userId); eq("status", "active") }
            }.decodeList<MemberRow>().singleOrNull() ?: throw FamilyAccessDenied()
            if (member.userId != identity.userId || member.familyId != familyId || member.status != "active" || member.role !in setOf("owner", "parent")) throw FamilyAccessDenied()
            val family = client.postgrest.from("families").select { filter { eq("id", familyId) } }
                .decodeList<FamilyRow>().singleOrNull() ?: throw FamilyAccessDenied()
            val children = client.postgrest.from("children").select { filter { eq("family_id", familyId) } }.decodeList<ChildRow>()
            val devices = client.postgrest.from("devices_public").select { filter { eq("family_id", familyId) } }.decodeList<DeviceRow>()
            val profile = client.postgrest.from("profiles").select { filter { eq("id", identity.userId) } }.decodeList<ProfileRow>().singleOrNull()
            FamilySnapshot(family.public(), member.public(), children.map { row -> row.public() }, devices.map { row -> row.public() }, now(), profile?.public())
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
    private fun requireUuid(value: String) { require(UUID.fromString(value).toString() == value.lowercase()) { "Invalid resource identifier" } }
}

@Serializable private data class FamilyRow(val id: String, val name: String, val timezone: String,
    @SerialName("created_at") val createdAt: String, @SerialName("updated_at") val updatedAt: String) {
    fun public() = FamilyV1(1, id, name, timezone, createdAt, updatedAt)
}
@Serializable private data class MemberRow(val id: String, @SerialName("family_id") val familyId: String,
    @SerialName("user_id") val userId: String, val role: String, val status: String,
    @SerialName("created_at") val createdAt: String, @SerialName("updated_at") val updatedAt: String) {
    fun public() = FamilyMemberV1(1, id, familyId, userId, role, status, createdAt, updatedAt)
}
@Serializable private data class ChildRow(val id: String, @SerialName("family_id") val familyId: String,
    @SerialName("display_name") val displayName: String, @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String) {
    fun public() = ChildV1(1, id, familyId, displayName, createdAt, updatedAt)
}
@Serializable private data class DeviceRow(val id: String, @SerialName("family_id") val familyId: String,
    @SerialName("child_id") val childId: String, @SerialName("display_name") val displayName: String,
    @SerialName("supervision_mode") val supervisionMode: String, val status: String,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String, @SerialName("updated_at") val updatedAt: String) {
    fun public() = DevicePublicV1(1, id, familyId, childId, displayName, supervisionMode, status, lastSeenAt, createdAt, updatedAt)
}
@Serializable private data class ProfileRow(val id: String, @SerialName("display_name") val displayName: String? = null,
    @SerialName("created_at") val createdAt: String, @SerialName("updated_at") val updatedAt: String) {
    fun public() = ProfilePublic(id, displayName, createdAt, updatedAt)
}
