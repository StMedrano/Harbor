package dev.stmedrano.harbor.parent.child

import kotlinx.serialization.Serializable

@Serializable data class ChildCredentials(val accessToken: String, val refreshToken: String, val expiresAt: Long) {
    override fun toString() = "ChildCredentials(redacted)"
}
@Serializable data class ChildAuthSession(val credentials: ChildCredentials, val userId: String, val anonymous: Boolean)
@Serializable data class ChildBinding(val deviceId: String, val familyId: String, val childId: String)
@Serializable data class ChildRecord(val session: ChildAuthSession, val binding: ChildBinding?, val revoked: Boolean = false,
    val lastSuccessAt: Long? = null, val desiredVersion: Long? = null)
enum class ChildFailure { AUTH_INVALID, KEY_LOST, REVOKED, UNKNOWN_OUTCOME, STORAGE_UNAVAILABLE }
sealed interface PairResult {
    data class Confirmed(val binding: ChildBinding) : PairResult
    data object Rejected : PairResult
    data object UnknownOutcome : PairResult
}
sealed interface ChildSyncState {
    data class Fresh(val receivedAt: Long, val desiredVersion: Long) : ChildSyncState
    data class Stale(val lastSuccessAt: Long?) : ChildSyncState
    data class Blocked(val reason: ChildFailure) : ChildSyncState
}
interface ChildStore {
    var claimPending: Boolean
    val hasHistory: Boolean get() = claimPending || load() != null
    fun load(): ChildRecord?
    fun save(record: ChildRecord)
    fun clear()
}
interface ChildSigner {
    fun exists(): Boolean
    fun publicKeySpki(): String
    fun sign(bytes: ByteArray): ByteArray
    fun delete()
}
interface ChildBackend {
    suspend fun anonymousSignup(): ChildAuthSession
    suspend fun refresh(session: ChildAuthSession): ChildAuthSession
    suspend fun claim(code: String, publicKeySpki: String, session: ChildAuthSession): ChildBinding
    suspend fun sync(binding: ChildBinding, session: ChildAuthSession): Long
    suspend fun registerFcm(binding: ChildBinding, token: String, session: ChildAuthSession)
}
class ChildRequestDenied(val status: Int, val code: String) : IllegalStateException("Child request denied")
