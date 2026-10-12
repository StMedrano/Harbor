package dev.stmedrano.harbor.parent.alerts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for the message-alert functions; limits mirror packages/contracts/src/v1/alerts.ts.
 * An alert names a category, an app and a time. There is deliberately no field that could carry
 * message text, a sender, a number or an excerpt.
 */
@Serializable enum class AlertCategory {
    @SerialName("bullying") BULLYING,
    @SerialName("self_harm") SELF_HARM,
    @SerialName("adult_content") ADULT_CONTENT,
    @SerialName("drugs_alcohol") DRUGS_ALCOHOL,
    @SerialName("violence_threat") VIOLENCE_THREAT,
    @SerialName("unknown_adult_contact") UNKNOWN_ADULT_CONTACT,
}
@Serializable data class MessageAlert(val id: String, val appLabel: String, val category: AlertCategory,
    val ruleSetVersion: Int, val at: String, val savedContact: Boolean)
@Serializable data class AlertReportV1(val version: Int, val epochId: String, val sequence: Long, val observedAt: String,
    val notificationAccess: Boolean, val smsPermission: Boolean, val alerts: List<MessageAlert>)
@Serializable data class AlertWriteReplyV1(val confirmed: Boolean, val sequence: Long, val receivedAt: String)
@Serializable data class AlertReadRequestV1(val version: Int = 1, val before: String?, val limit: Int)
@Serializable enum class AlertReadState { @SerialName("available") AVAILABLE, @SerialName("none") NONE, @SerialName("expired") EXPIRED }
@Serializable data class AlertReadReplyV1(val state: AlertReadState, val notificationAccess: Boolean?, val smsPermission: Boolean?,
    val observedAt: String?, val receivedAt: String?, val alerts: List<MessageAlert>, val nextBefore: String?)
@Serializable data class AlertCheckpointReplyV1(val sequence: Long, val epochId: String?)

const val MAX_ALERT_BYTES = 65_536
const val MAX_ALERTS_PER_REPORT = 50
const val MAX_ALERT_PAGE = 50
const val MAX_ALERT_LABEL = 64
const val ALERT_RETENTION_MS = 30L * 86_400_000L
private const val MAX_SAFE = 9_007_199_254_740_991L
private val alertUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val alertInstant = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$")
private val controlChars = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")

fun alertInstantMs(value: String): Long {
    require(alertInstant.matches(value))
    val parsed = java.time.Instant.parse(value)
    require(parsed.toString().replace(".000Z", "Z") == value.replace(".000Z", "Z"))
    return parsed.toEpochMilli()
}
/** Seconds resolution, always `YYYY-MM-DDTHH:MM:SSZ`, which the contract accepts. */
fun alertInstantString(ms: Long): String = java.time.Instant.ofEpochSecond(Math.floorDiv(ms, 1000L)).toString()

/** Returns the alert time in epoch milliseconds after checking every field. */
fun validateMessageAlert(alert: MessageAlert): Long {
    require(alertUuid.matches(alert.id))
    require(alert.appLabel.isNotEmpty() && alert.appLabel.codePointCount(0, alert.appLabel.length) <= MAX_ALERT_LABEL)
    require(!controlChars.containsMatchIn(alert.appLabel))
    require(alert.ruleSetVersion in 1..1_000_000)
    return alertInstantMs(alert.at)
}
fun validateAlertReport(value: AlertReportV1, nowMs: Long): AlertReportV1 {
    require(Json.encodeToString(AlertReportV1.serializer(), value).toByteArray(Charsets.UTF_8).size <= MAX_ALERT_BYTES)
    require(value.version == 1 && alertUuid.matches(value.epochId) && value.sequence in 1..MAX_SAFE)
    val observed = alertInstantMs(value.observedAt)
    require(observed - nowMs <= 300_000)
    require(value.alerts.size <= MAX_ALERTS_PER_REPORT)
    val ids = HashSet<String>()
    var previous = Long.MIN_VALUE
    value.alerts.forEach { alert ->
        val at = validateMessageAlert(alert)
        require(at <= observed && at >= observed - ALERT_RETENTION_MS && at >= previous)
        require(ids.add(alert.id.lowercase()))
        previous = at
    }
    return value
}
fun decodeAlertCheckpoint(raw: String): AlertCheckpointReplyV1 {
    val reply = Json.decodeFromString<AlertCheckpointReplyV1>(raw)
    require(reply.sequence in 0..MAX_SAFE)
    if (reply.sequence == 0L) require(reply.epochId == null) else require(reply.epochId != null && alertUuid.matches(reply.epochId))
    return reply
}
fun decodeAlertWriteReply(raw: String, sequence: Long): AlertWriteReplyV1 {
    val reply = Json.decodeFromString<AlertWriteReplyV1>(raw)
    check(reply.confirmed && reply.sequence == sequence) { "Alert write unconfirmed" }
    java.time.Instant.parse(reply.receivedAt)
    return reply
}
/** Strict read-side decode: an `available` reply is complete and newest-first; anything else is empty. */
fun decodeAlertRead(raw: String): AlertReadReplyV1 {
    val reply = Json { ignoreUnknownKeys = false }.decodeFromString<AlertReadReplyV1>(raw)
    require(reply.alerts.size <= MAX_ALERT_PAGE)
    if (reply.state == AlertReadState.AVAILABLE) {
        require(reply.notificationAccess != null && reply.smsPermission != null)
        alertInstantMs(checkNotNull(reply.observedAt))
        alertInstantMs(checkNotNull(reply.receivedAt))
    } else {
        require(reply.notificationAccess == null && reply.smsPermission == null && reply.observedAt == null && reply.receivedAt == null)
        require(reply.alerts.isEmpty() && reply.nextBefore == null)
    }
    var previous = Long.MAX_VALUE
    reply.alerts.forEach { alert ->
        val at = validateMessageAlert(alert)
        require(at <= previous)
        previous = at
    }
    reply.nextBefore?.let { alertInstantMs(it); require(reply.alerts.isNotEmpty()) }
    return reply
}
