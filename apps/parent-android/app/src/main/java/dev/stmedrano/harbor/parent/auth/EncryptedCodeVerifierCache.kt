package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.auth.CodeVerifierCache

class EncryptedCodeVerifierCache(private val store: SecureAuthStore) : CodeVerifierCache {
    override suspend fun saveCodeVerifier(codeVerifier: String) = store.write("verifier", codeVerifier)
    override suspend fun loadCodeVerifier(): String? = store.read("verifier")
    override suspend fun deleteCodeVerifier() {
        if (!store.retainingCodeVerifier) store.write("verifier", null)
    }
}
