package dev.stmedrano.harbor.parent.notifications

interface FirebaseTokenProvider {
    suspend fun token(): String
    suspend fun deleteToken()
}
