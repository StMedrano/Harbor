package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.auth.SessionManager
import io.github.jan.supabase.auth.exception.NoSessionFoundException
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.serialization.json.Json

class EncryptedSessionManager(private val store: SecureAuthStore) : SessionManager {
    // Persist the actual expiry, including a constructor default. Recomputing
    // expiresAt during decoding would extend a restored session's lifetime.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun saveSession(session: UserSession) = store.write("session", json.encodeToString(session))
    override suspend fun loadSession(): UserSession {
        val value = store.read("session") ?: throw NoSessionFoundException()
        return try { json.decodeFromString<UserSession>(value) }
        catch (_: Exception) { store.clear(); throw AuthStorageLost() }
    }
    override suspend fun deleteSession() = store.write("session", null)
}
