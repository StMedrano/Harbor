package dev.stmedrano.harbor.parent.notifications

import kotlinx.serialization.Serializable
import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.serialization.json.*

@Serializable data class ParentRegistrationReply(val registrationId: String, val active: Boolean)
interface ParentFcmApi {
    suspend fun register(installationId: String, token: String): ParentRegistrationReply
    suspend fun remove(installationId: String, accessToken: String)
}

class SdkParentFcmApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentFcmApi {
    override suspend fun register(installationId: String, token: String): ParentRegistrationReply = auth.withAccessToken { access ->
        val response = client.functions.invoke("register-parent-fcm") {
            headers.set(HttpHeaders.Authorization, "Bearer $access")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(buildJsonObject { put("clientInstallationId", installationId); put("token", token) }))
        }
        Json.decodeFromString<ParentRegistrationReply>(response.bodyAsText())
    }
    override suspend fun remove(installationId: String, accessToken: String) {
        require(accessToken.isNotBlank())
        client.functions.invoke("remove-parent-fcm") {
            // Cleanup uses the captured in-memory owner token, never a later SDK
            // account's mutable session or a token supplied in the request body.
            headers.set(HttpHeaders.Authorization, "Bearer $accessToken")
            contentType(ContentType.Application.Json)
            setBody(Json.encodeToString(buildJsonObject { put("clientInstallationId", installationId) }))
        }
    }
}
