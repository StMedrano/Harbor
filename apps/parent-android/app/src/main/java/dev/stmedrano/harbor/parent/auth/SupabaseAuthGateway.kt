package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.auth.SignOutScope
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.HttpClientEngine
import java.util.Locale
import java.util.UUID

class SupabaseAuthGateway(private val client: SupabaseClient, private val store: SecureAuthStore) : AuthGateway {
    override suspend fun signIn(email: String, password: String): UserSession {
        client.auth.signInWith(Email) { this.email = email; this.password = password }
        return client.auth.currentSessionOrNull() ?: throw AuthSessionRejected()
    }

    override suspend fun signUp(email: String, password: String): String =
        client.auth.signUpWith(Email, redirectUrl = CALLBACK) { this.email = email; this.password = password }
            ?.id?.takeIf { it.isNotBlank() } ?: throw AuthSessionRejected()

    override suspend fun requestRecovery(email: String) = client.auth.resetPasswordForEmail(email, redirectUrl = CALLBACK)
    override suspend fun exchangeCode(code: String) = client.auth.exchangeCodeForSession(code)

    override suspend fun fetchVerifiedIdentity(session: UserSession, expectedEmail: String?, expectedUserId: String?): ParentIdentity {
        val claims = client.auth.getClaims(session.accessToken).claims
        val user = client.auth.retrieveUser(session.accessToken)
        val sessionId = claims.sessionId ?: throw AuthSessionRejected()
        val subject = claims.sub ?: throw AuthSessionRejected()
        val email = user.email
        if (user.id != subject || user.isAnonymous != false || claims.isAnonymous != false ||
            user.emailConfirmedAt == null || email.isNullOrBlank() ||
            (expectedUserId != null && user.id != expectedUserId) ||
            (expectedEmail != null && email.trim().lowercase(Locale.ROOT) != expectedEmail)) throw AuthSessionRejected()
        try { UUID.fromString(subject); UUID.fromString(sessionId) }
        catch (_: IllegalArgumentException) { throw AuthSessionRejected() }
        return ParentIdentity(subject, sessionId)
    }

    override suspend fun changePassword(password: String) { client.auth.updateUser { this.password = password } }

    override suspend fun restoreStoredSession(): UserSession? {
        val session = EncryptedSessionManager(store).loadSessionOrNull() ?: return null
        client.auth.importSession(session, autoRefresh = false)
        return session
    }

    override suspend fun refresh(): UserSession = try {
        client.auth.refreshCurrentSession()
        client.auth.currentSessionOrNull() ?: throw AuthSessionRejected()
    } catch (failure: RestException) {
        if (failure.statusCode in setOf(400, 401)) throw AuthSessionRejected()
        throw failure
    }

    override suspend fun signOutCurrent() = client.auth.signOut(SignOutScope.LOCAL)
    override suspend fun clearLocalSession() = client.auth.clearSession()

    companion object {
        const val CALLBACK = "harbor-parent://auth/callback"
        @OptIn(SupabaseInternal::class)
        fun createClient(url: String, publishableKey: String, store: SecureAuthStore, engine: HttpClientEngine? = null): SupabaseClient =
            createSupabaseClient(url, publishableKey) {
                defaultLogLevel = LogLevel.NONE
                if (engine != null) httpEngine = engine
                install(Auth) {
                    sessionManager = EncryptedSessionManager(store)
                    codeVerifierCache = EncryptedCodeVerifierCache(store)
                    flowType = FlowType.PKCE
                    defaultRedirectUrl = CALLBACK
                    autoLoadFromStorage = false
                    autoSaveToStorage = true
                    alwaysAutoRefresh = false
                    enableLifecycleCallbacks = false
                    // This app owns exact URI routing; SDK platform auto-handlers must not import unsolicited links.
                    autoSetupPlatform = false
                }
            }
    }
}
