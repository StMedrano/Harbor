package dev.stmedrano.harbor.parent.alerts

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

/** Parent-authenticated read of the last server-confirmed alerts. The server decides access; the body carries only the device id and paging. */
class SdkParentAlertsApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentAlertApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    override suspend fun read(deviceId: String, before: String?, limit: Int): AlertReadReplyV1 = auth.withAccessToken {
        require(UUID.fromString(deviceId).toString() == deviceId.lowercase()) { "Invalid resource identifier" }
        require(limit in 1..MAX_ALERT_PAGE)
        try {
            val payload = buildJsonObject {
                put("version", 1)
                put("deviceId", deviceId)
                put("before", before)
                put("limit", limit)
            }
            val body = client.functions.invoke("get-message-alerts", payload, headers = jsonHeaders).bodyAsText()
            require(body.toByteArray(Charsets.UTF_8).size <= 600_000)
            decodeAlertRead(body)
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
}
