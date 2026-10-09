package dev.stmedrano.harbor.parent.usage

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

/** Parent-authenticated read of the last server-confirmed report. The server decides access; the body carries only the device id. */
class SdkParentUsageApi(private val client: SupabaseClient, private val auth: ParentAuthRepository,
    private val now: () -> Long = System::currentTimeMillis) : ParentUsageApi {
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    override suspend fun read(deviceId: String): UsageReadReplyV1 = auth.withAccessToken {
        require(UUID.fromString(deviceId).toString() == deviceId.lowercase()) { "Invalid resource identifier" }
        try {
            val body = client.functions.invoke("get-device-usage", buildJsonObject { put("deviceId", deviceId) }, headers = jsonHeaders).bodyAsText()
            require(body.toByteArray(Charsets.UTF_8).size <= 1_048_576 + 4096)
            val reply = json.decodeFromString<UsageReadReplyV1>(body)
            reply.report?.let { validateUsageReport(it, now()) }
            reply
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
}
