package dev.stmedrano.harbor.parent.auth

data class ParentIdentity(val userId: String, val sessionId: String)
class AuthSessionRejected : IllegalStateException("Parent session rejected; sign in again")
