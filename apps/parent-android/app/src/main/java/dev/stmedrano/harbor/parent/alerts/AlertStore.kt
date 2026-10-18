package dev.stmedrano.harbor.parent.alerts

import dev.stmedrano.harbor.parent.auth.AuthCipher
import dev.stmedrano.harbor.parent.auth.AuthStorageLost
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.usage.UsageHistory
import dev.stmedrano.harbor.parent.usage.UsageHistoryState
import kotlinx.serialization.Serializable

/** Exact bytes of a report not yet confirmed; a retry re-sends these bytes unchanged. */
@Serializable data class PendingAlert(val report: AlertReportV1, val bodyUtf8: String, val hash: String)

@Serializable data class AlertStoredState(
    val bindingId: String,
    val epochId: String,
    val nextSequence: Long = 1,
    /** Alerts not yet confirmed, oldest first, deduped by id, capped at [MAX_QUEUED_ALERTS]. */
    val queue: List<MessageAlert> = emptyList(),
    val pending: PendingAlert? = null,
    /** The access flags the server last confirmed; null until the first confirmed report. */
    val reportedNotificationAccess: Boolean? = null,
    val reportedSmsPermission: Boolean? = null,
    val lastConfirmedSequence: Long = 0,
    val receivedAt: String? = null,
)

/** Offline queue cap; the child cannot stop sharing, so a backlog is bounded by keeping the newest alerts only. */
const val MAX_QUEUED_ALERTS = 200

class AlertCheckpointLost : IllegalStateException("Alert checkpoint unavailable; verified recovery required")

interface AlertStore {
    fun read(bindingId: String): AlertStoredState?
    fun update(bindingId: String, change: (AlertStoredState) -> AlertStoredState): AlertStoredState
    fun eraseBinding(bindingId: String)
    /** Only call with a reply from the verified signed binding. */
    fun restoreCheckpoint(bindingId: String, reply: AlertCheckpointReplyV1): AlertStoredState
}

/**
 * Encrypted, child-binding-scoped store for message alerts. Same sequence-floor rollback protection as the usage and
 * location stores, in its own namespace. It holds text-free alert records only; no message text ever reaches it.
 */
class EncryptedAlertStore(
    private val values: AuthValues,
    cipher: AuthCipher,
    private val history: UsageHistory,
    private val monitor: Any = values,
    private val eraseKey: () -> Unit = {},
) : AlertStore {
    private val secure = SecureAuthStore(values, cipher)
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun loaded(bindingId: String): AlertStoredState? {
        val h = history.read()
        if (h != null && h.bindingHash != hash(bindingId)) return null
        val raw = try { secure.read("record") } catch (_: AuthStorageLost) { throw AlertCheckpointLost() }
        if (raw == null) { if (h != null) throw AlertCheckpointLost(); return null }
        val state = try { json.decodeFromString<AlertStoredState>(raw) } catch (_: Exception) { throw AlertCheckpointLost() }
        if (state.bindingId != bindingId) return null
        if (h == null || h.floor < 0 || state.nextSequence <= h.floor || state.nextSequence > 9_007_199_254_740_991L) throw AlertCheckpointLost()
        return state
    }
    private fun persist(state: AlertStoredState) {
        require(state.nextSequence in 1..9_007_199_254_740_991L && state.queue.size <= MAX_QUEUED_ALERTS)
        val encoded = json.encodeToString(AlertStoredState.serializer(), state)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Alert storage bound exceeded" }
        history.write(UsageHistoryState(hash(state.bindingId), state.nextSequence - 1))
        secure.write("record", encoded)
    }
    override fun read(bindingId: String): AlertStoredState? = synchronized(monitor) { loaded(bindingId) }
    override fun update(bindingId: String, change: (AlertStoredState) -> AlertStoredState): AlertStoredState = synchronized(monitor) {
        val h = history.read()
        check(h == null || h.bindingHash == hash(bindingId)) { "Foreign alert binding" }
        val state = loaded(bindingId) ?: AlertStoredState(bindingId, java.util.UUID.randomUUID().toString())
        val next = change(state)
        require(next.bindingId == bindingId && next.nextSequence >= state.nextSequence)
        persist(next)
        next
    }
    override fun eraseBinding(bindingId: String) = synchronized(monitor) {
        val h = history.read()
        if (h?.bindingHash == hash(bindingId)) { secure.clear(); history.clear(); eraseKey() }
    }
    override fun restoreCheckpoint(bindingId: String, reply: AlertCheckpointReplyV1): AlertStoredState = synchronized(monitor) {
        decodeAlertCheckpoint(json.encodeToString(AlertCheckpointReplyV1.serializer(), reply))
        val h = history.read()
        check(h == null || h.bindingHash == hash(bindingId)) { "Foreign alert binding" }
        val floor = maxOf(reply.sequence, h?.floor ?: 0L)
        require(floor < 9_007_199_254_740_991L)
        val state = AlertStoredState(bindingId, reply.epochId ?: java.util.UUID.randomUUID().toString(), nextSequence = floor + 1)
        persist(state)
        state
    }

    companion object {
        /** Separate namespace; never opens parent, child Auth, usage or location storage. */
        fun open(context: android.content.Context, name: String = "harbor-child-alerts"): EncryptedAlertStore {
            val prefs = context.applicationContext.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
            val marker = context.applicationContext.getSharedPreferences("$name-history", android.content.Context.MODE_PRIVATE)
            val values = object : AuthValues {
                override fun read(key: String) = prefs.getString(key, null)
                override fun write(key: String, value: String?) { check(prefs.edit().putString(key, value).commit()) }
                override fun clear() { check(prefs.edit().clear().commit()) }
            }
            val history = object : UsageHistory {
                override fun read(): UsageHistoryState? = marker.getString("binding", null)?.let { UsageHistoryState(it, marker.getLong("floor", -1)) }
                override fun write(value: UsageHistoryState) { check(marker.edit().putString("binding", value.bindingHash).putLong("floor", value.floor).commit()) }
                override fun clear() { check(marker.edit().clear().commit()) }
            }
            val alias = "$name-aes-v1"
            return EncryptedAlertStore(values, dev.stmedrano.harbor.parent.auth.KeystoreCipher(alias), history, prefs) {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            }
        }
    }
}
