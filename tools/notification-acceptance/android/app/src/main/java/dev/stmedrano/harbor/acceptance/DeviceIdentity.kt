package dev.stmedrano.harbor.acceptance

data class ChildSession(val accessToken: String, val refreshToken: String, val expiresAt: Long, val anonymous: Boolean)
data class DeviceBinding(val deviceId: String, val familyId: String, val childId: String)

class DeviceIdentity(
    private val clock: () -> Long,
    private val refresh: (String) -> ChildSession,
    private val save: (ChildSession) -> Unit
) {
    private var session: ChildSession? = null
    @Synchronized fun reset(clearPersisted: () -> Unit) {
        clearPersisted()
        session = null
        binding = null
    }
    var binding: DeviceBinding? = null
        private set

    @Synchronized fun acceptSession(value: ChildSession) {
        require(value.anonymous && value.accessToken.isNotBlank() && value.refreshToken.isNotBlank()) { "Only child anonymous sessions are allowed" }
        save(value)
        session = value
    }
    @Synchronized fun accessToken(): String {
        val current = checkNotNull(session) { "Enroll the child identity first" }
        if (current.expiresAt <= clock() + 30L) {
            val refreshed = refresh(current.refreshToken)
            require(refreshed.anonymous && refreshed.expiresAt > clock() + 30L) { "Child session refresh failed" }
            acceptSession(refreshed)
        }
        return checkNotNull(session).accessToken
    }
    @Synchronized fun acceptBinding(value: DeviceBinding) {
        val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        require(listOf(value.deviceId, value.familyId, value.childId).all { uuid.matches(it) }) { "Invalid device claim" }
        binding = value
    }
}
