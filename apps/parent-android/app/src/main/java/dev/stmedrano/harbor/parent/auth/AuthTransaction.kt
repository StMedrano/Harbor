package dev.stmedrano.harbor.parent.auth

import kotlinx.serialization.Serializable

@Serializable enum class AuthKind { SIGNUP, RECOVERY }

@Serializable data class AuthTransaction(
    val kind: AuthKind,
    val expectedEmail: String,
    val knownSubject: String?,
    val startedAtMillis: Long,
    val acceptedRecoverySubject: String? = null,
)
