package dev.stmedrano.harbor.parent.auth

import io.github.jan.supabase.auth.user.UserSession

interface AuthGateway {
    suspend fun signIn(email: String, password: String): UserSession
    suspend fun signUp(email: String, password: String): String
    suspend fun requestRecovery(email: String)
    suspend fun exchangeCode(code: String): UserSession
    suspend fun fetchVerifiedIdentity(session: UserSession, expectedEmail: String?, expectedUserId: String?): ParentIdentity
    suspend fun changePassword(password: String)
    suspend fun restoreStoredSession(): UserSession?
    suspend fun refresh(): UserSession
    suspend fun signOutCurrent()
    suspend fun clearLocalSession()
}
