package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.activity.ActivityKind
import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.auth.AuthCipher
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import dev.stmedrano.harbor.parent.child.DesiredSync
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The policy this phone is enforcing and the desired-state version it came from. */
@Serializable data class AppliedControls(val bindingId: String, val version: Long, val controls: ControlsV1)

interface ControlsStore {
    fun read(bindingId: String): AppliedControls?
    fun save(value: AppliedControls)
    fun eraseBinding(bindingId: String)
}

/** Encrypted, child-binding-scoped copy of the applied policy, so enforcement keeps working offline and after a reboot. */
class EncryptedControlsStore(values: AuthValues, cipher: AuthCipher) : ControlsStore {
    private val secure = SecureAuthStore(values, cipher)
    private val json = Json { encodeDefaults = true; explicitNulls = true }
    override fun read(bindingId: String): AppliedControls? = synchronized(this) {
        val raw = try { secure.read("policy") } catch (_: Exception) { null } ?: return null
        val value = try { json.decodeFromString(AppliedControls.serializer(), raw) } catch (_: Exception) { return null }
        if (value.bindingId != bindingId) return null
        try { validateControls(value.controls) } catch (_: Exception) { return null }
        value
    }
    override fun save(value: AppliedControls) = synchronized(this) {
        validateControls(value.controls)
        require(value.version >= 0)
        secure.write("policy", json.encodeToString(AppliedControls.serializer(), value))
    }
    override fun eraseBinding(bindingId: String) = synchronized(this) {
        if (read(bindingId) != null) secure.clear()
    }

    companion object {
        /** Separate namespace; never opens parent, child auth, usage, location or alert storage. */
        fun open(context: android.content.Context, name: String = "harbor-child-controls"): EncryptedControlsStore {
            val prefs = context.applicationContext.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
            val values = object : AuthValues {
                override fun read(key: String) = prefs.getString(key, null)
                override fun write(key: String, value: String?) { check(prefs.edit().putString(key, value).commit()) }
                override fun clear() { check(prefs.edit().clear().commit()) }
            }
            return EncryptedControlsStore(values, dev.stmedrano.harbor.parent.auth.KeystoreCipher("$name-aes-v1"))
        }
    }
}

/** One thing the controls did, handed to the activity recorder. Text-free: an app, a kind, a time and a reason. */
data class ControlEvent(val kind: ActivityKind, val atMs: Long, val packageName: String? = null, val appLabel: String? = null,
    val reason: BlockReason? = null)

/**
 * The hand-off between the child runtime (which owns the binding and the policy) and the accessibility service (which
 * Android starts and stops on its own). The service only reads the policy and reports events; it never sees the binding.
 */
object ControlsBridge {
    @Volatile var policy: ControlsV1? = null
        private set
    @Volatile var recorder: ((ControlEvent) -> Unit)? = null
    private val connected = MutableStateFlow(false)
    /** True while Android has Harbor's accessibility service bound, i.e. controls are actually being enforced. */
    val serviceConnected: StateFlow<Boolean> = connected.asStateFlow()
    /** Set by the accessibility service so a new policy (a pause, a new block) applies to the app already on screen. */
    @Volatile var onPolicyChanged: (() -> Unit)? = null
    fun publish(value: ControlsV1?) {
        val changed = policy != value
        policy = value
        if (changed) try { onPolicyChanged?.invoke() } catch (_: Exception) { /* The next window change or tick re-checks. */ }
    }
    fun setConnected(value: Boolean) { connected.value = value }
    fun record(event: ControlEvent) { try { recorder?.invoke(event) } catch (_: Exception) { /* Recording never blocks enforcement. */ } }
}

data class ControlsHostState(val appliedVersion: Long? = null, val controls: ControlsV1? = null)

/**
 * Keeps app controls applied for one child profile. It takes each desired state the child repository syncs, stores the
 * policy encrypted, publishes it to the accessibility service, and acknowledges the version so the parent can see it
 * is in force. A controls object that fails validation is never applied and never acknowledged.
 */
class ChildControlsHost(
    private val store: ControlsStore,
    private val current: (ProfileLease) -> Boolean,
    private val acknowledge: (Long) -> Unit,
    private val requestSync: suspend () -> Unit,
    private val publish: (ControlsV1?) -> Unit = ControlsBridge::publish,
) {
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(ControlsHostState())
    val state: StateFlow<ControlsHostState> = mutableState.asStateFlow()
    @Volatile private var active: ProfileLease? = null
    private var acknowledgedSent = -1L

    private fun valid(lease: ProfileLease) = active == lease && lease.role == ProfileRole.CHILD && current(lease)

    /** Loads the stored policy so enforcement resumes before the first sync (offline, after reboot). */
    suspend fun start(lease: ProfileLease) = lock.withLock {
        if (lease.role != ProfileRole.CHILD) return@withLock
        active = lease
        val stored = try { store.read(lease.ownerId) } catch (_: Exception) { null }
        if (stored != null) {
            publish(stored.controls)
            mutableState.value = ControlsHostState(stored.version, stored.controls)
            acknowledge(stored.version)
        }
    }

    suspend fun apply(lease: ProfileLease, desired: DesiredSync) {
        val newlyApplied = lock.withLock {
            if (!valid(lease)) return
            val known = mutableState.value.appliedVersion
            if (known != null && desired.version < known) return
            val controls = try {
                desired.controlsJson?.let(::decodeControls) ?: ControlsV1()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return } // Never apply or acknowledge a policy that fails the contract.
            if (known != desired.version || mutableState.value.controls != controls) {
                store.save(AppliedControls(lease.ownerId, desired.version, controls))
                publish(controls)
                mutableState.value = ControlsHostState(desired.version, controls)
            }
            acknowledge(desired.version)
            (desired.version > acknowledgedSent).also { if (it) acknowledgedSent = desired.version }
        }
        // One follow-up sync carries the acknowledgment now instead of on the next scheduled sync.
        if (newlyApplied) try { requestSync() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
    }

    /** Revocation or profile change: stop enforcing immediately. Only a confirmed revocation erases the stored policy. */
    suspend fun stop(eraseBindingId: String? = null) = lock.withLock {
        active = null
        publish(null)
        mutableState.value = ControlsHostState()
        acknowledgedSent = -1L
        eraseBindingId?.let { try { store.eraseBinding(it) } catch (_: Exception) {} }
    }
}

/**
 * Foreground time of one app between [fromMs] and [toMs], from that app's resume/pause transitions in time order.
 * A session still open at [toMs] counts up to [toMs]; one already open before [fromMs] counts from [fromMs].
 */
fun foregroundMs(transitions: List<Pair<Long, Boolean>>, fromMs: Long, toMs: Long): Long {
    var total = 0L
    var openedAt: Long? = null
    for ((at, resumed) in transitions.sortedBy { it.first }) {
        if (resumed) { if (openedAt == null) openedAt = at }
        else openedAt?.let { start -> total += maxOf(0L, minOf(at, toMs) - maxOf(start, fromMs)); openedAt = null }
    }
    openedAt?.let { start -> total += maxOf(0L, toMs - maxOf(start, fromMs)) }
    return total
}

/** Whether the rule that blocked [packageName] no longer applies: a parent's yes, a resume, or an unblock. */
fun blockLifted(packageName: String, reason: BlockReason, policy: ControlsV1?, now: java.time.ZonedDateTime = java.time.ZonedDateTime.now()): Boolean {
    if (policy == null) return false // Not loaded yet: never claim a yes that may not exist.
    if (PolicyEngine.grantRemainingMs(packageName, now, policy) != null || packageName in policy.alwaysAllowed) return true
    return when (reason) {
        BlockReason.PAUSED -> !PolicyEngine.isPaused(now, policy)
        BlockReason.BEDTIME -> !PolicyEngine.isBedtime(now, policy)
        BlockReason.BLOCKED_APP -> packageName !in policy.blockedApps
        BlockReason.LIMIT_REACHED -> false // Time used today does not shrink; only a yes or extra time reopens it.
    }
}

/** The words the child sees on the block screen. Plain and specific; never a fake system message. */
fun blockMessage(reason: BlockReason, appLabel: String, policy: ControlsV1?, daily: Boolean = false): Pair<String, String> = when {
    daily -> "Time's up for today" to
        "You've used today's screen time. Only apps a parent set to Unlimited work until tomorrow. Calls and texts still work."
    else -> blockReasonMessage(reason, appLabel, policy)
}
private fun blockReasonMessage(reason: BlockReason, appLabel: String, policy: ControlsV1?): Pair<String, String> = when (reason) {
    BlockReason.PAUSED -> "Your phone has been paused by Harbor Family" to
        (policy?.pause?.until?.let { "A parent paused your phone. It unlocks at ${localClock(it)}." }
            ?: "A parent paused your phone. It unlocks when they resume it.")
    BlockReason.BEDTIME -> "It's bedtime" to
        (policy?.bedtime?.let { "$appLabel is off until ${it.end}. Phone calls and texts still work." } ?: "$appLabel is off for now.")
    BlockReason.BLOCKED_APP -> "$appLabel is blocked by Harbor Family" to "A parent blocked this app on your phone."
    BlockReason.LIMIT_REACHED -> "Time's up for $appLabel" to "You've used today's time for this app. It's available again tomorrow."
}
private fun localClock(instant: String): String = try {
    java.time.Instant.parse(instant).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withSecond(0).withNano(0).toString()
} catch (_: Exception) { "later" }
