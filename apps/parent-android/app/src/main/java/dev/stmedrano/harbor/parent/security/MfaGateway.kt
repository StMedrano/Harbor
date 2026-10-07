package dev.stmedrano.harbor.parent.security

import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.mfa.AuthenticatorAssuranceLevel
import io.github.jan.supabase.auth.mfa.FactorType
import java.util.UUID

// Enrollment material lives only in memory and is never included in status logs.
class TotpEnrollment(val factorId: String, val secret: String, val qrUri: String) {
    override fun toString() = "TotpEnrollment([redacted])"
}
interface MfaGateway {
    suspend fun enrollTotp(): TotpEnrollment
    suspend fun challenge(factorId: String, code: String)
    suspend fun listFactors(): List<String>
}
class MfaRequired : IllegalStateException("Fresh authenticator verification required")

class SdkMfaGateway(private val client: SupabaseClient, private val auth: ParentAuthRepository) : MfaGateway {
    override suspend fun enrollTotp(): TotpEnrollment = auth.withAccessToken { token ->
        requireSdkSession(token)
        val factor = client.auth.mfa.enroll(FactorType.TOTP, friendlyName = "Harbor Parent")
        requireFactor(factor.id)
        TotpEnrollment(factor.id, factor.data.secret, factor.data.uri)
    }
    override suspend fun listFactors(): List<String> = auth.withAccessToken { token ->
        requireSdkSession(token)
        client.auth.mfa.retrieveFactorsForCurrentUser().filter { it.isVerified && it.factorType == "totp" }
            .map { it.id.also(::requireFactor) }
    }
    override suspend fun challenge(factorId: String, code: String) {
        requireFactor(factorId)
        require(code.matches(Regex("[0-9]{6}")))
        val owner = checkNotNull(auth.identity.value)
        val returned = auth.withAccessToken { token ->
            requireSdkSession(token)
            check(auth.identity.value == owner)
            val challenge = client.auth.mfa.createChallenge(factorId)
            val result = client.auth.mfa.verifyChallenge(factorId, challenge.id, code, saveSession = false)
            // Use supported verified claims, never SDK's raw-JWT MFA convenience parser.
            if (client.auth.getClaims(result.accessToken).claims.aal != AuthenticatorAssuranceLevel.AAL2) throw MfaRequired()
            result
        }
        auth.acceptMfaSession(owner, returned) { client.auth.importSession(it, autoRefresh = false) }
    }
    private fun requireSdkSession(token: String) { check(client.auth.currentAccessTokenOrNull() == token) { "SDK session changed; sign in again" } }
    private fun requireFactor(value: String) { require(UUID.fromString(value).toString() == value.lowercase()) }
}
