package dev.stmedrano.harbor.parent.familymap

import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/** Parent-authenticated family-map calls. The server decides access; bodies carry only what the contract allows. */
class SdkParentFamilyMapApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentFamilyMapApi {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val json = Json { ignoreUnknownKeys = false }

    private suspend fun call(function: String, body: JsonObject): String = auth.withAccessToken {
        try {
            val text = client.functions.invoke(function, body, headers = jsonHeaders).bodyAsText()
            require(text.toByteArray(Charsets.UTF_8).size <= 20_000)
            text
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
    private fun family(familyId: String): String {
        require(UUID.fromString(familyId).toString() == familyId.lowercase()) { "Invalid resource identifier" }
        return familyId
    }
    override suspend fun settings(familyId: String): FamilyMapSettingsV1 =
        decodeFamilyMapSettings(call("get-family-map-settings", buildJsonObject { put("version", 1); put("familyId", family(familyId)) }))
    override suspend fun setChildrenCanView(familyId: String, value: Boolean): FamilyMapSettingsV1 =
        decodeFamilyMapSettings(call("set-family-map-settings", buildJsonObject { put("version", 1); put("familyId", family(familyId)); put("childrenCanView", value) }))
    override suspend fun report(report: ParentLocationReportV1): ParentLocationReceiptV1 = decodeParentLocationReceipt(call("report-parent-location",
        buildJsonObject {
            put("version", 1); put("familyId", family(report.familyId)); put("label", report.label)
            put("fix", buildJsonObject { put("at", report.fix.at); put("latitude", report.fix.latitude); put("longitude", report.fix.longitude); put("accuracyM", report.fix.accuracyM) })
        }))
    override suspend fun stop(familyId: String): ParentLocationReceiptV1 =
        decodeParentLocationReceipt(call("stop-parent-location", buildJsonObject { put("version", 1); put("familyId", family(familyId)) }))
}
