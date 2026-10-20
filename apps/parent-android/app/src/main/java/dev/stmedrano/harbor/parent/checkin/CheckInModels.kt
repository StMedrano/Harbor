package dev.stmedrano.harbor.parent.checkin

import dev.stmedrano.harbor.parent.location.locationInstantMs
import dev.stmedrano.harbor.parent.location.sixDecimals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for check-ins; limits mirror packages/contracts/src/v1/checkin.ts. A check-in is a parent asking and the
 * child answering with one of two presets. There is no free text, no SOS and nothing a child can start unasked.
 */
@Serializable enum class CheckInPreset { @SerialName("ok") OK, @SerialName("late") LATE }
@Serializable enum class CheckInStatus { @SerialName("pending") PENDING, @SerialName("answered") ANSWERED, @SerialName("expired") EXPIRED }
@Serializable data class CheckInFix(val at: String, val latitude: Double, val longitude: Double, val accuracyM: Int)
@Serializable data class CheckInReplyV1(val version: Int, val requestId: String, val preset: CheckInPreset, val fix: CheckInFix?)
@Serializable data class CheckInReceiptV1(val confirmed: Boolean, val receivedAt: String)
@Serializable data class CheckInPending(val requestId: String, val requestedAt: String, val expiresAt: String)
@Serializable data class CheckInRecent(val requestId: String, val requestedAt: String, val repliedAt: String, val preset: CheckInPreset, val located: Boolean)
@Serializable data class CheckInStateV1(val pending: CheckInPending?, val recent: List<CheckInRecent>)
@Serializable data class CheckInRequestReplyV1(val requestId: String, val requestedAt: String, val expiresAt: String, val created: Boolean)
@Serializable data class CheckInEntryReply(val repliedAt: String, val preset: CheckInPreset, val fix: CheckInFix?)
@Serializable data class CheckInEntry(val requestId: String, val requestedAt: String, val expiresAt: String, val status: CheckInStatus, val reply: CheckInEntryReply?)
@Serializable data class CheckInReadV1(val checkins: List<CheckInEntry>)

const val MAX_CHECKIN_BYTES = 4_096
const val CHECKIN_TTL_MS = 24L * 3_600_000L
const val CHECKIN_RECENT_LIMIT = 10
const val CHECKIN_READ_LIMIT = 20
/** A reply may carry a place no older than this, so a stale position is never presented as "now". */
const val CHECKIN_FIX_MAX_AGE_MS = 3_600_000L
private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")

private fun requireUuid(value: String) = require(uuid.matches(value))

fun validateCheckInFix(fix: CheckInFix, nowMs: Long?) {
    require(fix.latitude.isFinite() && kotlin.math.abs(fix.latitude) <= 90.0 && sixDecimals(fix.latitude))
    require(fix.longitude.isFinite() && kotlin.math.abs(fix.longitude) <= 180.0 && sixDecimals(fix.longitude))
    require(fix.accuracyM in 0..100_000)
    val at = locationInstantMs(fix.at)
    if (nowMs != null) require(at <= nowMs + 300_000 && at >= nowMs - CHECKIN_FIX_MAX_AGE_MS)
}
fun validateCheckInReply(value: CheckInReplyV1, nowMs: Long): CheckInReplyV1 {
    require(Json.encodeToString(CheckInReplyV1.serializer(), value).toByteArray(Charsets.UTF_8).size <= MAX_CHECKIN_BYTES)
    require(value.version == 1)
    requireUuid(value.requestId)
    value.fix?.let { validateCheckInFix(it, nowMs) }
    return value
}
private fun validatePending(p: CheckInPending) {
    requireUuid(p.requestId)
    val at = locationInstantMs(p.requestedAt); val expires = locationInstantMs(p.expiresAt)
    require(expires > at && expires - at <= CHECKIN_TTL_MS)
}
fun decodeCheckInState(raw: String): CheckInStateV1 {
    require(raw.toByteArray(Charsets.UTF_8).size <= 65_536)
    val state = Json.decodeFromString<CheckInStateV1>(raw)
    state.pending?.let(::validatePending)
    require(state.recent.size <= CHECKIN_RECENT_LIMIT)
    var previous = Long.MAX_VALUE
    state.recent.forEach { item ->
        requireUuid(item.requestId)
        val requested = locationInstantMs(item.requestedAt); val replied = locationInstantMs(item.repliedAt)
        require(replied >= requested && replied <= previous) // newest first
        previous = replied
    }
    return state
}
fun decodeCheckInReceipt(raw: String): CheckInReceiptV1 {
    val receipt = Json.decodeFromString<CheckInReceiptV1>(raw)
    check(receipt.confirmed) { "Check-in reply unconfirmed" }
    locationInstantMs(receipt.receivedAt)
    return receipt
}
fun decodeCheckInRequestReply(raw: String): CheckInRequestReplyV1 {
    val reply = Json.decodeFromString<CheckInRequestReplyV1>(raw)
    validatePending(CheckInPending(reply.requestId, reply.requestedAt, reply.expiresAt))
    return reply
}
fun decodeCheckInRead(raw: String): CheckInReadV1 {
    require(raw.toByteArray(Charsets.UTF_8).size <= 131_072)
    val read = Json.decodeFromString<CheckInReadV1>(raw)
    require(read.checkins.size <= CHECKIN_READ_LIMIT)
    var previous = Long.MAX_VALUE
    read.checkins.forEach { item ->
        requireUuid(item.requestId)
        val requested = locationInstantMs(item.requestedAt)
        require(locationInstantMs(item.expiresAt) > requested && requested <= previous) // newest first
        previous = requested
        if (item.status == CheckInStatus.ANSWERED) {
            val reply = requireNotNull(item.reply)
            require(locationInstantMs(reply.repliedAt) >= requested)
            reply.fix?.let { validateCheckInFix(it, null) }
        } else require(item.reply == null)
    }
    return read
}
