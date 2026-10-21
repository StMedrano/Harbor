package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * App controls as they travel in `desiredState.controls`; limits and rules mirror packages/contracts/src/v1/controls.ts.
 * Controls work on whole apps only: there is no field for a website, a contact or any content.
 */
@Serializable enum class Weekday {
    @SerialName("mon") MON, @SerialName("tue") TUE, @SerialName("wed") WED, @SerialName("thu") THU,
    @SerialName("fri") FRI, @SerialName("sat") SAT, @SerialName("sun") SUN;
    companion object {
        fun of(day: java.time.DayOfWeek): Weekday = entries[day.value - 1]
    }
}
@Serializable data class ControlsPause(val active: Boolean, val until: String?)
@Serializable data class ControlsBedtime(val enabled: Boolean, val start: String, val end: String, val days: List<Weekday>)
@Serializable data class AppLimit(val packageName: String, val minutesPerDay: Int)
/**
 * Total screen time a day, a hard stop: when it is used up only always-allowed ("Unlimited") apps and the exempt apps
 * work until midnight. Always-allowed apps do not count toward it. [extraMinutes] apply only on [extraDate] (the child
 * phone's local date), for "Give 15 more min".
 */
@Serializable data class DailyLimit(val minutes: Int, val extraDate: String? = null, val extraMinutes: Int = 0) {
    fun allowanceMinutes(today: java.time.LocalDate): Int = minutes + if (extraDate == today.toString()) extraMinutes else 0
}
@Serializable data class ControlsV1(
    val version: Int = 1,
    val pause: ControlsPause = ControlsPause(false, null),
    val bedtime: ControlsBedtime? = null,
    val blockedApps: List<String> = emptyList(),
    val appLimits: List<AppLimit> = emptyList(),
    val alwaysAllowed: List<String> = emptyList(),
    val dailyLimit: DailyLimit? = null,
)

const val CONTROLS_MAX_BLOCKED = 200
const val CONTROLS_MAX_LIMITS = 200
const val CONTROLS_MAX_ALLOWED = 50
const val CONTROLS_MAX_BYTES = 32_768
private val controlsJson = Json { ignoreUnknownKeys = false; explicitNulls = true; encodeDefaults = true }
private val packagePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
private val clockPattern = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

fun isPackageName(value: String) = value.length <= 255 && packagePattern.matches(value)
/** Minutes after local midnight for an `HH:MM` clock value. */
fun clockMinutes(value: String): Int {
    require(clockPattern.matches(value))
    return value.substring(0, 2).toInt() * 60 + value.substring(3, 5).toInt()
}

fun validateControls(value: ControlsV1): ControlsV1 {
    require(controlsJson.encodeToString(ControlsV1.serializer(), value).toByteArray(Charsets.UTF_8).size <= CONTROLS_MAX_BYTES)
    require(value.version == 1)
    value.pause.until?.let { require(value.pause.active); alertInstantMs(it) }
    value.bedtime?.let { b ->
        require(clockMinutes(b.start) != clockMinutes(b.end))
        require(b.days.size == b.days.toSet().size)
        if (b.enabled) require(b.days.isNotEmpty())
    }
    val blocked = packageSet(value.blockedApps, CONTROLS_MAX_BLOCKED)
    val allowed = packageSet(value.alwaysAllowed, CONTROLS_MAX_ALLOWED)
    require(value.appLimits.size <= CONTROLS_MAX_LIMITS)
    val limited = HashSet<String>()
    value.appLimits.forEach { require(isPackageName(it.packageName) && limited.add(it.packageName) && it.minutesPerDay in 1..1440) }
    // One rule per app: blocked, limited, always allowed, or none of these.
    require(blocked.none { it in allowed || it in limited } && limited.none { it in allowed })
    value.dailyLimit?.let { d ->
        require(d.minutes in 1..1440 && d.extraMinutes in 0..720)
        if (d.extraDate == null) require(d.extraMinutes == 0) else require(runCatching { java.time.LocalDate.parse(d.extraDate).toString() == d.extraDate }.getOrDefault(false))
    }
    return value
}
private fun packageSet(values: List<String>, max: Int): Set<String> {
    require(values.size <= max && values.all(::isPackageName))
    return values.toSet().also { require(it.size == values.size) }
}

/**
 * Strict decode of `desiredState.controls`; anything malformed is rejected rather than half-applied. The data classes
 * have defaults for building policies in code, so the exact key set is checked here: a missing key is not "default".
 */
fun decodeControls(raw: String): ControlsV1 {
    val root = controlsJson.parseToJsonElement(raw).jsonObject
    // A policy written before the daily limit existed has no `dailyLimit` key and reads as "no daily limit".
    val legacy = setOf("version", "pause", "bedtime", "blockedApps", "appLimits", "alwaysAllowed")
    require(root.keys == legacy || root.keys == legacy + "dailyLimit")
    (root["dailyLimit"] as? JsonObject)?.let { require(it.keys == setOf("minutes", "extraDate", "extraMinutes")) }
    require(root.getValue("pause").jsonObject.keys == setOf("active", "until"))
    (root.getValue("bedtime") as? JsonObject)?.let { require(it.keys == setOf("enabled", "start", "end", "days")) }
    root.getValue("appLimits").jsonArray.forEach { require(it.jsonObject.keys == setOf("packageName", "minutesPerDay")) }
    return validateControls(controlsJson.decodeFromJsonElement(ControlsV1.serializer(), root))
}
fun encodeControls(value: ControlsV1): String = controlsJson.encodeToString(ControlsV1.serializer(), validateControls(value))
