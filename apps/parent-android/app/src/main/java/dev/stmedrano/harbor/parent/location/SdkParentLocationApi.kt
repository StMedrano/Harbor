package dev.stmedrano.harbor.parent.location

import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Parent-authenticated read of the last server-confirmed places. The server decides access; the body carries only the device id. */
class SdkParentLocationApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentLocationApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    override suspend fun read(deviceId: String): LocationReadReplyV1 = auth.withAccessToken {
        require(UUID.fromString(deviceId).toString() == deviceId.lowercase()) { "Invalid resource identifier" }
        try {
            val body = client.functions.invoke("get-device-location", buildJsonObject { put("deviceId", deviceId) }, headers = jsonHeaders).bodyAsText()
            require(body.toByteArray(Charsets.UTF_8).size <= 600_000)
            validateLocationRead(json.decodeFromString<LocationReadReplyV1>(body))
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
}

/** Newest first, bounded, every place valid. A reply that breaks these is treated as a failed load, not shown. */
fun validateLocationRead(reply: LocationReadReplyV1): LocationReadReplyV1 {
    require(reply.fixes.size <= 500)
    var previous = Long.MAX_VALUE
    reply.fixes.forEach { fix ->
        validateLocationFix(fix)
        val at = locationInstantMs(fix.at)
        require(at < previous); previous = at
    }
    if (reply.state != LocationReadState.AVAILABLE) require(reply.fixes.isEmpty() && reply.status == null)
    reply.receivedAt?.let { locationInstantMsLenient(it) }
    return reply
}
