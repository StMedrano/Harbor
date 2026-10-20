package dev.stmedrano.harbor.parent.activity

import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import dev.stmedrano.harbor.parent.controls.isPackageName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for the device-activity functions; limits mirror packages/contracts/src/v1/activity.ts.
 * An event names an app and times. There is deliberately no field for screen content, window titles, web addresses
 * or any text.
 */
@Serializable enum class ActivityKind {
    @SerialName("app_session") APP_SESSION,
    @SerialName("blocked_open") BLOCKED_OPEN,
    @SerialName("limit_reached") LIMIT_REACHED,
    @SerialName("pause_started") PAUSE_STARTED,
    @SerialName("pause_ended") PAUSE_ENDED,
    @SerialName("bedtime_started") BEDTIME_STARTED,
    @SerialName("bedtime_ended") BEDTIME_ENDED,
    @SerialName("protection_off") PROTECTION_OFF,
    @SerialName("protection_on") PROTECTION_ON;
    val isAppEvent get() = this == APP_SESSION || this == BLOCKED_OPEN || this == LIMIT_REACHED
}
@Serializable enum class BlockReason {
    @SerialName("paused") PAUSED, @SerialName("bedtime") BEDTIME,
    @SerialName("blocked_app") BLOCKED_APP, @SerialName("limit_reached") LIMIT_REACHED,
}
@Serializable data class ActivityEvent(val id: String, val kind: ActivityKind, val at: String, val endAt: String?,
    val packageName: String?, val appLabel: String?, val reason: BlockReason?)
@Serializable data class ActivityStatus(val controlsOn: Boolean, val policyVersion: Long)
@Serializable data class ActivityReportV1(val version: Int, val epochId: String, val sequence: Long, val observedAt: String,
    val status: ActivityStatus, val events: List<ActivityEvent>)
@Serializable data class ActivityWriteReplyV1(val confirmed: Boolean, val sequence: Long, val receivedAt: String)
@Serializable data class ActivityCheckpointReplyV1(val sequence: Long, val epochId: String?)
@Serializable data class ActivityReadRequestV1(val version: Int = 1, val from: String, val to: String)
@Serializable enum class ActivityReadState { @SerialName("available") AVAILABLE, @SerialName("none") NONE, @SerialName("expired") EXPIRED }
@Serializable data class ActivityReadReplyV1(val state: ActivityReadState, val controlsOn: Boolean?, val policyVersion: Long?,
    val observedAt: String?, val receivedAt: String?, val events: List<ActivityEvent>, val truncated: Boolean)

const val ACTIVITY_MAX_BYTES = 65_536
const val ACTIVITY_MAX_PER_REPORT = 100
const val ACTIVITY_MAX_READ = 500
const val ACTIVITY_MAX_WINDOW_MS = 48L * 3_600_000L
const val ACTIVITY_RETENTION_MS = 30L * 86_400_000L
const val ACTIVITY_MIN_SESSION_MS = 60_000L
const val ACTIVITY_MAX_SESSION_MS = 24L * 3_600_000L
const val ACTIVITY_LABEL_MAX = 64
private const val MAX_SAFE = 9_007_199_254_740_991L
private val activityUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val controlChars = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")
private val strict = Json { ignoreUnknownKeys = false; explicitNulls = true }

/** Returns the event time in epoch milliseconds after checking every field. */
fun validateActivityEvent(event: ActivityEvent): Long {
    require(activityUuid.matches(event.id))
    val at = alertInstantMs(event.at)
    if (event.kind.isAppEvent) {
        val label = requireNotNull(event.appLabel)
        require(isPackageName(requireNotNull(event.packageName)))
        require(label.isNotEmpty() && label.codePointCount(0, label.length) <= ACTIVITY_LABEL_MAX && !controlChars.containsMatchIn(label))
    } else {
        require(event.packageName == null && event.appLabel == null)
    }
    if (event.kind == ActivityKind.APP_SESSION) {
        val length = alertInstantMs(requireNotNull(event.endAt)) - at
        require(length in ACTIVITY_MIN_SESSION_MS..ACTIVITY_MAX_SESSION_MS)
    } else {
        require(event.endAt == null)
    }
    if (event.kind == ActivityKind.BLOCKED_OPEN) require(event.reason != null) else require(event.reason == null)
    return at
}
fun validateActivityReport(value: ActivityReportV1, nowMs: Long): ActivityReportV1 {
    require(strict.encodeToString(ActivityReportV1.serializer(), value).toByteArray(Charsets.UTF_8).size <= ACTIVITY_MAX_BYTES)
    require(value.version == 1 && activityUuid.matches(value.epochId) && value.sequence in 1..MAX_SAFE)
    require(value.status.policyVersion in 0..MAX_SAFE)
    val observed = alertInstantMs(value.observedAt)
    require(observed - nowMs <= 300_000)
    require(value.events.size <= ACTIVITY_MAX_PER_REPORT)
    val ids = HashSet<String>()
    var previous = Long.MIN_VALUE
    value.events.forEach { event ->
        val at = validateActivityEvent(event)
        require(at <= observed && at >= observed - ACTIVITY_RETENTION_MS && at >= previous)
        require(ids.add(event.id.lowercase()))
        previous = at
    }
    return value
}
fun encodeActivityReport(value: ActivityReportV1): String = strict.encodeToString(ActivityReportV1.serializer(), value)
fun decodeActivityCheckpoint(raw: String): ActivityCheckpointReplyV1 {
    val reply = strict.decodeFromString<ActivityCheckpointReplyV1>(raw)
    require(reply.sequence in 0..MAX_SAFE)
    if (reply.sequence == 0L) require(reply.epochId == null) else require(reply.epochId != null && activityUuid.matches(reply.epochId))
    return reply
}
fun decodeActivityWriteReply(raw: String, sequence: Long): ActivityWriteReplyV1 {
    val reply = strict.decodeFromString<ActivityWriteReplyV1>(raw)
    check(reply.confirmed && reply.sequence == sequence) { "Activity write unconfirmed" }
    alertInstantMs(reply.receivedAt.replace(Regex("\\.\\d{3}Z$"), "Z"))
    return reply
}
/** Strict read-side decode: an `available` reply is complete and oldest-first; anything else is empty. */
fun decodeActivityRead(raw: String): ActivityReadReplyV1 {
    val reply = strict.decodeFromString<ActivityReadReplyV1>(raw)
    require(reply.events.size <= ACTIVITY_MAX_READ)
    if (reply.state == ActivityReadState.AVAILABLE) {
        require(reply.controlsOn != null && reply.policyVersion != null && reply.policyVersion in 0..MAX_SAFE)
        alertInstantMs(requireNotNull(reply.observedAt))
        alertInstantMs(requireNotNull(reply.receivedAt))
    } else {
        require(reply.controlsOn == null && reply.policyVersion == null && reply.observedAt == null && reply.receivedAt == null)
        require(reply.events.isEmpty() && !reply.truncated)
    }
    var previous = Long.MIN_VALUE
    reply.events.forEach { e -> val at = validateActivityEvent(e); require(at >= previous); previous = at }
    return reply
}
