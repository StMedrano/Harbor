package dev.stmedrano.harbor.parent.location

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

fun interface ParentLocationApi { suspend fun read(deviceId: String): LocationReadReplyV1 }

enum class LocationViewStatus { AVAILABLE, NONE, EXPIRED, ACCESS_LOST, LOAD_FAILED }
const val LOCATION_STALE_AFTER_MS = 30L * 60_000L

/** What a parent sees. Built only from a server reply; `fixes` are newest first. */
data class LocationViewState(
    val status: LocationViewStatus,
    val deviceStatus: LocationStatus? = null,
    val fixes: List<LocationFix> = emptyList(),
    val receivedAtMs: Long? = null,
    /** True when this is the previous reply shown again because a refresh failed. */
    val offline: Boolean = false,
) {
    val latest: LocationFix? get() = fixes.firstOrNull()
    /** Nothing new from the child's phone for a while; the map is then labelled instead of looking live. */
    fun isStale(nowMs: Long): Boolean = receivedAtMs?.let { nowMs - it > LOCATION_STALE_AFTER_MS } ?: true

    companion object {
        fun fromReply(reply: LocationReadReplyV1, nowMs: Long, offline: Boolean = false): LocationViewState = when (reply.state) {
            LocationReadState.AVAILABLE -> LocationViewState(LocationViewStatus.AVAILABLE, reply.status, reply.fixes,
                reply.receivedAt?.let(::locationInstantMsLenient), offline)
            LocationReadState.NONE -> LocationViewState(LocationViewStatus.NONE, offline = offline)
            LocationReadState.EXPIRED -> LocationViewState(LocationViewStatus.EXPIRED, offline = offline)
        }
    }
}
/** Server timestamps may carry milliseconds. */
fun locationInstantMsLenient(value: String): Long = java.time.Instant.parse(value).toEpochMilli()

/** The message a parent reads for a device status; null when sharing is healthy. */
fun locationStatusMessage(status: LocationStatus?): String? = when (status) {
    LocationStatus.PERMISSION_LOST -> "Location permission was turned off on this phone, so no new places are arriving."
    LocationStatus.SERVICES_OFF -> "Location is switched off on this phone, so no new places are arriving."
    LocationStatus.BACKGROUND_MISSING -> "This phone only shares while Harbor is open, so updates may have gaps."
    LocationStatus.SHARING, null -> null
}

/** "just now", "5 min ago", "3 h ago", "2 days ago". */
fun locationAgeLabel(ageMs: Long): String {
    val minutes = ageMs / 60_000L
    return when {
        ageMs < 0 || minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

/**
 * Memory-only, read-only parent location. Every reply is fenced by the captured identity, device and request order;
 * access loss replaces (never keeps) a previous place, and nothing is persisted.
 */
class ParentLocationModel(private val api: ParentLocationApi, private val currentIdentity: () -> ParentIdentity?,
    private val now: () -> Long = System::currentTimeMillis) {
    data class Scope(val identity: ParentIdentity, val deviceId: String)
    data class Scoped(val scope: Scope, val view: LocationViewState)
    private val mutable = MutableStateFlow<Scoped?>(null)
    val state: StateFlow<Scoped?> = mutable.asStateFlow()
    private val generation = AtomicLong()
    private var lastReply: Pair<Scope, LocationReadReplyV1>? = null

    suspend fun load(identity: ParentIdentity, deviceId: String) {
        val scope = Scope(identity, deviceId)
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live()) return
        val previous = lastReply?.takeIf { it.first == scope }?.second
        try {
            val reply = api.read(deviceId)
            if (!live()) return
            lastReply = scope to reply
            mutable.value = Scoped(scope, LocationViewState.fromReply(reply, now()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FamilyAccessDenied) {
            if (!live()) return
            lastReply = null
            mutable.value = Scoped(scope, LocationViewState(LocationViewStatus.ACCESS_LOST))
        } catch (_: Exception) {
            if (!live()) return
            mutable.value = Scoped(scope, if (previous != null) LocationViewState.fromReply(previous, now(), offline = true)
                else LocationViewState(LocationViewStatus.LOAD_FAILED))
        }
    }

    /** Sign-out, membership loss or role change: drop everything and invalidate in-flight reads. */
    fun clear() { generation.incrementAndGet(); lastReply = null; mutable.value = null }
}
