package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
@Serializable data class ControlsV1(
    val version: Int = 1,
    val pause: ControlsPause = ControlsPause(false, null),
    val bedtime: ControlsBedtime? = null,
    val blockedApps: List<String> = emptyList(),
    val appLimits: List<AppLimit> = emptyList(),
    val alwaysAllowed: List<String> = emptyList(),
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
    return value
}
private fun packageSet(values: List<String>, max: Int): Set<String> {
    require(values.size <= max && values.all(::isPackageName))
    return values.toSet().also { require(it.size == values.size) }
}

/** Strict decode of `desiredState.controls`; anything malformed is rejected rather than half-applied. */
fun decodeControls(raw: String): ControlsV1 = validateControls(controlsJson.decodeFromString(ControlsV1.serializer(), raw))
fun encodeControls(value: ControlsV1): String = controlsJson.encodeToString(ControlsV1.serializer(), validateControls(value))
