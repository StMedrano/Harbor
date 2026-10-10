package dev.stmedrano.harbor.parent.location

/** What the phone can currently do for location sharing. Pure data so the mapping is unit tested. */
data class LocationAccess(val fineGranted: Boolean, val backgroundGranted: Boolean, val servicesOn: Boolean)

/**
 * The status the parent is shown. Order matters: a missing permission is reported before a switched-off setting, because
 * the child can only fix the permission inside this app, while the setting is a system toggle.
 */
fun locationStatus(access: LocationAccess): LocationStatus = when {
    !access.fineGranted -> LocationStatus.PERMISSION_LOST
    !access.servicesOn -> LocationStatus.SERVICES_OFF
    !access.backgroundGranted -> LocationStatus.BACKGROUND_MISSING
    else -> LocationStatus.SHARING
}

/**
 * Whether the background service may run. Fine location is mandatory. Without background access Android only allows a
 * location service that was started while this app was visible, so the service is attempted and failures are tolerated.
 */
fun locationServiceAllowed(access: LocationAccess): Boolean = access.fineGranted && access.servicesOn

object LocationCadence {
    /** Providers are asked every minute; fixes closer together than this are not stored. */
    const val REQUEST_INTERVAL_MS = 60_000L
    const val MIN_DISTANCE_M = 25f
    const val MIN_SPACING_MS = 45_000L
    /** A weak fix is still kept if nothing better arrived for this long, so a parent always sees the last known place. */
    const val WEAK_FIX_PATIENCE_MS = 10 * 60_000L
    const val WEAK_ACCURACY_M = 1_000
    /** The engine is flushed this often at most while fixes arrive. */
    const val FLUSH_INTERVAL_MS = 60_000L
    /** Re-check permission/setting changes and resend status this often while the service runs. */
    const val SUPERVISE_INTERVAL_MS = 5 * 60_000L

    fun shouldRecord(last: LocationFix?, next: LocationFix): Boolean {
        if (last == null) return true
        val gap = locationInstantMs(next.at) - locationInstantMs(last.at)
        if (gap <= 0) return false
        if (gap < MIN_SPACING_MS) return false
        if (next.accuracyM > WEAK_ACCURACY_M && gap < WEAK_FIX_PATIENCE_MS) return false
        return true
    }
}

/** Rounds to the contract's six decimals and clamps the accuracy to an integer metre value. */
fun toLocationFix(atMs: Long, latitude: Double, longitude: Double, accuracyMeters: Float?, provider: String?): LocationFix? {
    val kind = when (provider) {
        "gps" -> LocationProvider.GPS
        "network" -> LocationProvider.NETWORK
        "fused" -> LocationProvider.FUSED
        else -> return null
    }
    if (!latitude.isFinite() || !longitude.isFinite()) return null
    val lat = java.math.BigDecimal.valueOf(latitude).setScale(6, java.math.RoundingMode.HALF_UP).toDouble()
    val lon = java.math.BigDecimal.valueOf(longitude).setScale(6, java.math.RoundingMode.HALF_UP).toDouble()
    if (kotlin.math.abs(lat) > 90.0 || kotlin.math.abs(lon) > 180.0) return null
    val accuracy = accuracyMeters?.takeIf { it.isFinite() && it >= 0f }?.let { kotlin.math.ceil(it.toDouble()).toInt().coerceIn(0, 100_000) } ?: return null
    return LocationFix(locationInstantString(atMs), lat, lon, accuracy, kind)
}
