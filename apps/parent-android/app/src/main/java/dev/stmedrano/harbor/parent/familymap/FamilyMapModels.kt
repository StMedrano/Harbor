package dev.stmedrano.harbor.parent.familymap

import dev.stmedrano.harbor.parent.location.locationInstantMs
import dev.stmedrano.harbor.parent.location.sixDecimals
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire types for the family map; limits mirror packages/contracts/src/v1/familymap.ts. A child sees the family's children
 * and the parents who chose to share. There is no history, no alert and no way to message anyone from here.
 */
@Serializable enum class PersonKind { @SerialName("child") CHILD, @SerialName("parent") PARENT }
@Serializable data class FamilyMapFix(val at: String, val latitude: Double, val longitude: Double, val accuracyM: Int)
@Serializable data class FamilyMapPerson(val id: String, val kind: PersonKind, val name: String, val self: Boolean, val fix: FamilyMapFix?)
@Serializable data class FamilyMapReplyV1(val enabled: Boolean, val people: List<FamilyMapPerson>)

/**
 * One row of public.family_places, a parent's own read of the family map. The child's phone gets the same picture
 * from get-family-map, which it may call because it carries a device proof; a parent reads this view instead.
 */
@Serializable data class FamilyPlaceRow(
    val kind: PersonKind,
    @SerialName("person_id") val personId: String,
    @SerialName("user_id") val userId: String? = null,
    val name: String,
    @SerialName("fix_at") val fixAt: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyM: Int? = null,
) {
    /** Null when this person has sent no place yet; they are still listed, never dropped. */
    fun person(selfUserId: String): FamilyMapPerson = FamilyMapPerson(
        personId, kind, name,
        self = kind == PersonKind.PARENT && userId != null && userId == selfUserId,
        fix = if (fixAt != null && latitude != null && longitude != null) FamilyMapFix(fixAt, latitude, longitude, accuracyM ?: 0) else null,
    )
}
@Serializable data class ParentLocationReportV1(val version: Int, val familyId: String, val label: String, val fix: FamilyMapFix)
@Serializable data class ParentLocationReceiptV1(val confirmed: Boolean, val receivedAt: String)
@Serializable data class MySharing(val label: String, val lastAt: String)
@Serializable data class FamilyMapSettingsV1(val childrenCanView: Boolean, val mySharing: MySharing?)

const val FAMILY_MAP_MAX_PEOPLE = 50
const val FAMILY_MAP_LABEL_MAX = 40
const val FAMILY_MAP_MAX_BYTES = 4_096
/** A parent's report must carry a place no older than this, matching the server. */
const val PARENT_FIX_MAX_AGE_MS = 600_000L
private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-8][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$")
private val control = Regex("[\\u0000-\\u001f\\u007f-\\u009f]")

fun validFamilyMapLabel(value: String): Boolean =
    value.isNotEmpty() && value.codePointCount(0, value.length) <= FAMILY_MAP_LABEL_MAX && value == value.trim() && !control.containsMatchIn(value)

fun validateFamilyMapFix(fix: FamilyMapFix) {
    require(fix.latitude.isFinite() && kotlin.math.abs(fix.latitude) <= 90.0 && sixDecimals(fix.latitude))
    require(fix.longitude.isFinite() && kotlin.math.abs(fix.longitude) <= 180.0 && sixDecimals(fix.longitude))
    require(fix.accuracyM in 0..100_000)
    locationInstantMs(fix.at)
}

private val strict = Json { ignoreUnknownKeys = false }

fun decodeFamilyMap(raw: String): FamilyMapReplyV1 {
    require(raw.toByteArray(Charsets.UTF_8).size <= 65_536)
    val reply = strict.decodeFromString<FamilyMapReplyV1>(raw)
    require(reply.people.size <= FAMILY_MAP_MAX_PEOPLE)
    if (!reply.enabled) require(reply.people.isEmpty())
    val seen = HashSet<String>()
    var selves = 0
    reply.people.forEach { p ->
        require(uuid.matches(p.id) && seen.add(p.id.lowercase()))
        require(validFamilyMapLabel(p.name))
        if (p.self) { selves++; require(p.kind == PersonKind.CHILD) }
        if (p.kind == PersonKind.PARENT) require(p.fix != null)
        p.fix?.let(::validateFamilyMapFix)
    }
    require(selves <= 1)
    return reply
}

fun validateParentLocationReport(value: ParentLocationReportV1, nowMs: Long): ParentLocationReportV1 {
    require(strict.encodeToString(ParentLocationReportV1.serializer(), value).toByteArray(Charsets.UTF_8).size <= FAMILY_MAP_MAX_BYTES)
    require(value.version == 1 && uuid.matches(value.familyId) && validFamilyMapLabel(value.label))
    validateFamilyMapFix(value.fix)
    val at = locationInstantMs(value.fix.at)
    require(at <= nowMs + 300_000 && at >= nowMs - PARENT_FIX_MAX_AGE_MS)
    return value
}
fun decodeParentLocationReceipt(raw: String): ParentLocationReceiptV1 {
    val receipt = strict.decodeFromString<ParentLocationReceiptV1>(raw)
    check(receipt.confirmed) { "Parent location unconfirmed" }
    locationInstantMs(receipt.receivedAt)
    return receipt
}
fun decodeFamilyMapSettings(raw: String): FamilyMapSettingsV1 {
    val settings = strict.decodeFromString<FamilyMapSettingsV1>(raw)
    settings.mySharing?.let { require(validFamilyMapLabel(it.label)); locationInstantMs(it.lastAt) }
    return settings
}
