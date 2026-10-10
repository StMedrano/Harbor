package dev.stmedrano.harbor.parent.location

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire types for `report-device-location` and friends; limits mirror packages/contracts/src/v1/location.ts. */
@Serializable enum class LocationStatus {
    @SerialName("sharing") SHARING,
    @SerialName("permission_lost") PERMISSION_LOST,
    @SerialName("background_missing") BACKGROUND_MISSING,
    @SerialName("services_off") SERVICES_OFF,
}
@Serializable enum class LocationProvider { @SerialName("fused") FUSED, @SerialName("gps") GPS, @SerialName("network") NETWORK }
@Serializable data class LocationFix(val at: String, val latitude: Double, val longitude: Double, val accuracyM: Int, val provider: LocationProvider)
@Serializable data class LocationReportV1(val version: Int, val epochId: String, val sequence: Long, val observedAt: String,
    val status: LocationStatus, val fixes: List<LocationFix>)
@Serializable data class LocationWriteReplyV1(val confirmed: Boolean, val sequence: Long, val receivedAt: String)
@Serializable enum class LocationReadState { @SerialName("available") AVAILABLE, @SerialName("none") NONE, @SerialName("expired") EXPIRED }
@Serializable data class LocationReadReplyV1(val state: LocationReadState, val status: LocationStatus?, val observedAt: String?,
    val receivedAt: String?, val fixes: List<LocationFix>)
@Serializable data class LocationCheckpointReplyV1(val sequence: Long, val epochId: String?)

const val MAX_LOCATION_BYTES = 262_144
const val MAX_LOCATION_FIXES = 100
const val LOCATION_RETENTION_MS = 30L * 86_400_000L
private const val MAX_SAFE = 9_007_199_254_740_991L
private val locationUuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val locationInstant = Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{3})?Z$")

fun locationInstantMs(value: String): Long {
    require(locationInstant.matches(value))
    val parsed = java.time.Instant.parse(value)
    require(parsed.toString().replace(".000Z", "Z") == value.replace(".000Z", "Z"))
    return parsed.toEpochMilli()
}
/** Seconds resolution, always `YYYY-MM-DDTHH:MM:SSZ`, which the contract accepts. */
fun locationInstantString(ms: Long): String = java.time.Instant.ofEpochSecond(Math.floorDiv(ms, 1000L)).toString()
private fun sixDecimals(value: Double): Boolean = value.isFinite() && java.math.BigDecimal.valueOf(value).stripTrailingZeros().scale() <= 6

fun validateLocationFix(fix: LocationFix) {
    require(fix.latitude.isFinite() && kotlin.math.abs(fix.latitude) <= 90.0 && sixDecimals(fix.latitude))
    require(fix.longitude.isFinite() && kotlin.math.abs(fix.longitude) <= 180.0 && sixDecimals(fix.longitude))
    require(fix.accuracyM in 0..100_000)
    locationInstantMs(fix.at)
}
fun validateLocationReport(value: LocationReportV1, nowMs: Long): LocationReportV1 {
    require(Json.encodeToString(LocationReportV1.serializer(), value).toByteArray(Charsets.UTF_8).size <= MAX_LOCATION_BYTES)
    require(value.version == 1 && locationUuid.matches(value.epochId) && value.sequence in 1..MAX_SAFE)
    val observed = locationInstantMs(value.observedAt)
    require(observed - nowMs <= 300_000)
    require(value.fixes.size <= MAX_LOCATION_FIXES)
    var previous = Long.MIN_VALUE
    value.fixes.forEach { fix ->
        validateLocationFix(fix)
        val at = locationInstantMs(fix.at)
        require(at <= observed && at >= observed - LOCATION_RETENTION_MS && at > previous)
        previous = at
    }
    return value
}
fun decodeLocationCheckpoint(raw: String): LocationCheckpointReplyV1 {
    val reply = Json.decodeFromString<LocationCheckpointReplyV1>(raw)
    require(reply.sequence in 0..MAX_SAFE)
    if (reply.sequence == 0L) require(reply.epochId == null) else require(reply.epochId != null && locationUuid.matches(reply.epochId))
    return reply
}
fun decodeLocationWriteReply(raw: String, sequence: Long): LocationWriteReplyV1 {
    val reply = Json.decodeFromString<LocationWriteReplyV1>(raw)
    check(reply.confirmed && reply.sequence == sequence) { "Location write unconfirmed" }
    java.time.Instant.parse(reply.receivedAt)
    return reply
}
