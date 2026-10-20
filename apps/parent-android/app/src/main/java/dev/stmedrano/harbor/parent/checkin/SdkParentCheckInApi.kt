package dev.stmedrano.harbor.parent.checkin

import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Parent-authenticated check-in calls. The server decides access; each body carries only a version and the device id. */
class SdkParentCheckInApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentCheckInApi {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private suspend fun call(function: String, deviceId: String): String = auth.withAccessToken {
        require(UUID.fromString(deviceId).toString() == deviceId.lowercase()) { "Invalid resource identifier" }
        try {
            val body = client.functions.invoke(function, buildJsonObject { put("version", 1); put("deviceId", deviceId) }, headers = jsonHeaders).bodyAsText()
            require(body.toByteArray(Charsets.UTF_8).size <= 200_000)
            body
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
    override suspend fun read(deviceId: String): CheckInReadV1 = decodeCheckInRead(call("get-device-checkins", deviceId))
    override suspend fun request(deviceId: String): CheckInRequestReplyV1 = decodeCheckInRequestReply(call("request-device-checkin", deviceId))
}
