package dev.stmedrano.harbor.parent.activity

import dev.stmedrano.harbor.parent.alerts.alertInstantString
import dev.stmedrano.harbor.parent.controls.ControlEvent
import dev.stmedrano.harbor.parent.controls.ControlsV1
import dev.stmedrano.harbor.parent.controls.PolicyEngine
import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.CancellationException
import java.time.ZonedDateTime
import java.util.UUID

/** One app's foreground transition from the phone's usage events: resumed (true) or paused (false). */
data class AppTransition(val atMs: Long, val packageName: String, val resumed: Boolean)

/** A finished app session. */
data class AppSession(val packageName: String, val startMs: Long, val endMs: Long)

/**
 * Turns usage transitions after [cursorMs] into finished sessions of at least one minute. Back-to-back sessions of the
 * same app less than 5 seconds apart (moving between screens of one app) are joined. A session still open at [nowMs]
 * is not emitted; the returned cursor stays at its start so it is read whole next time. Exempt apps (home screen,
 * System UI, Harbor) are not sessions.
 */
fun sessionsFrom(transitions: List<AppTransition>, cursorMs: Long, nowMs: Long, exempt: Set<String>): Pair<List<AppSession>, Long> {
    val open = HashMap<String, Long>()
    val raw = ArrayList<AppSession>()
    for (t in transitions.filter { it.atMs in cursorMs..nowMs && it.packageName !in exempt }.sortedBy { it.atMs }) {
        if (t.resumed) open.putIfAbsent(t.packageName, t.atMs)
        else open.remove(t.packageName)?.let { start -> raw += AppSession(t.packageName, start, t.atMs) }
    }
    val joined = ArrayList<AppSession>()
    for (s in raw.sortedBy { it.startMs }) {
        val last = joined.lastOrNull()
        if (last != null && last.packageName == s.packageName && s.startMs - last.endMs < 5_000L) joined[joined.size - 1] = last.copy(endMs = s.endMs)
        else joined += s
    }
    val kept = joined.filter { it.endMs - it.startMs >= ACTIVITY_MIN_SESSION_MS }
        .map { if (it.endMs - it.startMs > ACTIVITY_MAX_SESSION_MS) it.copy(startMs = it.endMs - ACTIVITY_MAX_SESSION_MS) else it }
    val cursor = open.values.minOrNull() ?: nowMs
    return kept to cursor
}

/** Event ids derived from content, so the same session or change found twice is the same event and is deduplicated. */
fun stableEventId(vararg parts: String): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(parts.joinToString("|").toByteArray(Charsets.UTF_8))
    digest[6] = ((digest[6].toInt() and 0x0f) or 0x40).toByte()
    digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
    val bb = java.nio.ByteBuffer.wrap(digest, 0, 16)
    return UUID(bb.long, bb.long).toString()
}

fun sessionEvent(s: AppSession, label: String) = ActivityEvent(stableEventId("session", s.packageName, s.startMs.toString()),
    ActivityKind.APP_SESSION, alertInstantString(s.startMs), alertInstantString(s.endMs).let { end ->
        // Whole-second instants can round a 60 s session down to 59 s; such a session is reported as exactly 60 s.
        if (dev.stmedrano.harbor.parent.alerts.alertInstantMs(end) - dev.stmedrano.harbor.parent.alerts.alertInstantMs(alertInstantString(s.startMs)) < ACTIVITY_MIN_SESSION_MS)
            alertInstantString(dev.stmedrano.harbor.parent.alerts.alertInstantMs(alertInstantString(s.startMs)) + ACTIVITY_MIN_SESSION_MS) else end
    }, s.packageName, label, null)

fun controlEvent(e: ControlEvent): ActivityEvent = ActivityEvent(
    stableEventId(e.kind.name, e.packageName.orEmpty(), (e.atMs / 1000).toString()),
    e.kind, alertInstantString(e.atMs), null,
    e.packageName.takeIf { e.kind.isAppEvent }, e.appLabel?.take(ACTIVITY_LABEL_MAX).takeIf { e.kind.isAppEvent }, e.reason.takeIf { e.kind == ActivityKind.BLOCKED_OPEN },
)

/** Changes in pause, bedtime and protection since the last check, as events (each change exactly once). */
fun stateChanges(state: ActivityStoredState, now: ZonedDateTime, policy: ControlsV1?, controlsOn: Boolean): Pair<List<ControlEvent>, ActivityStoredState> {
    val at = now.toInstant().toEpochMilli()
    val paused = policy?.let { PolicyEngine.isPaused(now, it) } ?: false
    val bedtime = policy?.let { PolicyEngine.isBedtime(now, it) } ?: false
    val out = ArrayList<ControlEvent>()
    fun change(old: Boolean?, new: Boolean, on: ActivityKind, off: ActivityKind) {
        if (old != null && old != new) out += ControlEvent(if (new) on else off, at)
    }
    change(state.lastPaused, paused, ActivityKind.PAUSE_STARTED, ActivityKind.PAUSE_ENDED)
    change(state.lastBedtime, bedtime, ActivityKind.BEDTIME_STARTED, ActivityKind.BEDTIME_ENDED)
    change(state.lastControlsOn, controlsOn, ActivityKind.PROTECTION_ON, ActivityKind.PROTECTION_OFF)
    return out to state.copy(lastPaused = paused, lastBedtime = bedtime, lastControlsOn = controlsOn)
}

/**
 * Keeps activity reporting running for one child profile: on each pass it reads finished app sessions from the phone's
 * usage events, notes pause/bedtime/protection changes, adds events the controls service recorded, and uploads.
 */
class ChildActivityHost(
    private val reporter: ActivityReporter,
    private val current: (ProfileLease) -> Boolean,
    private val transitions: (fromMs: Long, toMs: Long) -> List<AppTransition>,
    private val exempt: () -> Set<String>,
    private val label: (String) -> String,
    private val policy: () -> ControlsV1?,
    private val policyVersion: () -> Long,
    private val controlsOn: () -> Boolean,
    private val now: () -> ZonedDateTime,
) {
    @Volatile private var active: ProfileLease? = null
    fun start(lease: ProfileLease) { if (lease.role == ProfileRole.CHILD) active = lease }
    fun stop() { active = null }
    private fun valid(lease: ProfileLease) = active == lease && current(lease)

    /** Called by the controls service through the bridge; events are queued and sent with the next pass. */
    suspend fun onControlEvent(lease: ProfileLease, event: ControlEvent) {
        if (!valid(lease)) return
        try { reporter.record(lease, listOf(controlEvent(event))) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
    }

    suspend fun supervise(lease: ProfileLease) {
        if (!valid(lease)) return
        val nowZ = now()
        val nowMs = nowZ.toInstant().toEpochMilli()
        val state = reporter.state(lease)
        val cursor = state?.sessionCursorMs ?: (nowMs - 3_600_000L)
        val (sessions, nextCursor) = sessionsFrom(transitions(cursor, nowMs), cursor, nowMs, exempt())
        val on = controlsOn()
        val (changes, _) = stateChanges(state ?: ActivityStoredState("", ""), nowZ, policy(), on)
        val events = sessions.map { sessionEvent(it, label(it.packageName)) } + changes.map(::controlEvent)
        reporter.record(lease, events) { s ->
            stateChanges(s, nowZ, policy(), on).second.copy(sessionCursorMs = maxOf(nextCursor, s.sessionCursorMs ?: 0L))
        }
        reporter.flush(lease, ActivityStatusNow(on, policyVersion()))
    }
}
