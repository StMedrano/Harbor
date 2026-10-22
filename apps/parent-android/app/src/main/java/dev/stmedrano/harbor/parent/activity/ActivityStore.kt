package dev.stmedrano.harbor.parent.activity

import dev.stmedrano.harbor.parent.auth.AuthCipher
import dev.stmedrano.harbor.parent.auth.AuthStorageLost
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.usage.UsageHistory
import dev.stmedrano.harbor.parent.usage.UsageHistoryState
import kotlinx.serialization.Serializable

/** Exact bytes of a report not yet confirmed; a retry re-sends these bytes unchanged. */
@Serializable data class PendingActivity(val report: ActivityReportV1, val bodyUtf8: String, val hash: String)

@Serializable data class ActivityStoredState(
    val bindingId: String,
    val epochId: String,
    val nextSequence: Long = 1,
    /** Events not yet confirmed, oldest first, deduped by id, capped at [MAX_QUEUED_ACTIVITY]. */
    val queue: List<ActivityEvent> = emptyList(),
    val pending: PendingActivity? = null,
    /** The status the server last confirmed; null until the first confirmed report. */
    val reportedControlsOn: Boolean? = null,
    val reportedPolicyVersion: Long? = null,
    /** The screen-time count the server last confirmed, so an unchanged count is not sent again. */
    val reportedScreenTime: ScreenTime? = null,
    val lastConfirmedSequence: Long = 0,
    val receivedAt: String? = null,
    /** App sessions are read from usage events after this instant; an open session holds it at its start. */
    val sessionCursorMs: Long? = null,
    /** Last observed states, so each change becomes exactly one event. */
    val lastPaused: Boolean? = null,
    val lastBedtime: Boolean? = null,
    val lastControlsOn: Boolean? = null,
)

/** Offline cap; the child cannot stop reporting, so a backlog keeps the newest events only. */
const val MAX_QUEUED_ACTIVITY = 500

class ActivityCheckpointLost : IllegalStateException("Activity checkpoint unavailable; verified recovery required")

interface ActivityStore {
    fun read(bindingId: String): ActivityStoredState?
    fun update(bindingId: String, change: (ActivityStoredState) -> ActivityStoredState): ActivityStoredState
    fun eraseBinding(bindingId: String)
    /** Only call with a reply from the verified signed binding. */
    fun restoreCheckpoint(bindingId: String, reply: ActivityCheckpointReplyV1): ActivityStoredState
}

/**
 * Encrypted, child-binding-scoped store for activity. Same sequence-floor rollback protection as the usage, location
 * and alert stores, in its own namespace. It holds app names, times and control events only.
 */
class EncryptedActivityStore(
    private val values: AuthValues,
    cipher: AuthCipher,
    private val history: UsageHistory,
    private val monitor: Any = values,
    private val eraseKey: () -> Unit = {},
) : ActivityStore {
    private val secure = SecureAuthStore(values, cipher)
    private val json = kotlinx.serialization.json.Json { encodeDefaults = true }
    private fun hash(value: String) = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun loaded(bindingId: String): ActivityStoredState? {
        val h = history.read()
        if (h != null && h.bindingHash != hash(bindingId)) return null
        val raw = try { secure.read("record") } catch (_: AuthStorageLost) { throw ActivityCheckpointLost() }
        if (raw == null) { if (h != null) throw ActivityCheckpointLost(); return null }
        val state = try { json.decodeFromString<ActivityStoredState>(raw) } catch (_: Exception) { throw ActivityCheckpointLost() }
        if (state.bindingId != bindingId) return null
        if (h == null || h.floor < 0 || state.nextSequence <= h.floor || state.nextSequence > 9_007_199_254_740_991L) throw ActivityCheckpointLost()
        return state
    }
    private fun persist(state: ActivityStoredState) {
        require(state.nextSequence in 1..9_007_199_254_740_991L && state.queue.size <= MAX_QUEUED_ACTIVITY)
        val encoded = json.encodeToString(ActivityStoredState.serializer(), state)
        require(encoded.toByteArray(Charsets.UTF_8).size <= 4 * 1024 * 1024) { "Activity storage bound exceeded" }
        history.write(UsageHistoryState(hash(state.bindingId), state.nextSequence - 1))
        secure.write("record", encoded)
    }
    override fun read(bindingId: String): ActivityStoredState? = synchronized(monitor) { loaded(bindingId) }
    override fun update(bindingId: String, change: (ActivityStoredState) -> ActivityStoredState): ActivityStoredState = synchronized(monitor) {
        val h = history.read()
        check(h == null || h.bindingHash == hash(bindingId)) { "Foreign activity binding" }
        val state = loaded(bindingId) ?: ActivityStoredState(bindingId, java.util.UUID.randomUUID().toString())
        val next = change(state)
        require(next.bindingId == bindingId && next.nextSequence >= state.nextSequence)
        persist(next)
        next
    }
    override fun eraseBinding(bindingId: String) = synchronized(monitor) {
        val h = history.read()
        if (h?.bindingHash == hash(bindingId)) { secure.clear(); history.clear(); eraseKey() }
    }
    override fun restoreCheckpoint(bindingId: String, reply: ActivityCheckpointReplyV1): ActivityStoredState = synchronized(monitor) {
        decodeActivityCheckpoint(json.encodeToString(ActivityCheckpointReplyV1.serializer(), reply))
        val h = history.read()
        check(h == null || h.bindingHash == hash(bindingId)) { "Foreign activity binding" }
        val floor = maxOf(reply.sequence, h?.floor ?: 0L)
        require(floor < 9_007_199_254_740_991L)
        val state = ActivityStoredState(bindingId, reply.epochId ?: java.util.UUID.randomUUID().toString(), nextSequence = floor + 1)
        persist(state)
        state
    }

    companion object {
        /** Separate namespace; never opens parent, child auth, usage, location, alert or controls storage. */
        fun open(context: android.content.Context, name: String = "harbor-child-activity"): EncryptedActivityStore {
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
            return EncryptedActivityStore(values, dev.stmedrano.harbor.parent.auth.KeystoreCipher(alias), history, prefs) {
                java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(alias) }
            }
        }
    }
}
