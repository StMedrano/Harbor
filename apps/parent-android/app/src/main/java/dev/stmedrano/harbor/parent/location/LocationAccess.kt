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
    /** Request at most one platform fix every five minutes; throttle stationary stored fixes to 15 minutes. */
    const val MOVING_INTERVAL_MS = 5 * 60_000L
    const val STATIONARY_INTERVAL_MS = 15 * 60_000L
    const val REQUEST_INTERVAL_MS = MOVING_INTERVAL_MS
    /** Zero distance threshold lets Android deliver a stationary fix for the 15-minute status/trail policy. */
    const val MIN_DISTANCE_M = 0f
    const val WEAK_ACCURACY_M = 1_000
    private const val MIN_MOVEMENT_M = 25.0
    private const val EARTH_RADIUS_M = 6_371_000.0
    /** Network flushes may occur once per minute, but only *accepted* fixes are included. */
    const val FLUSH_INTERVAL_MS = 60_000L
    /** Re-check permission/setting changes and resend status this often while the service runs. */
    const val SUPERVISE_INTERVAL_MS = 5 * 60_000L

    /** Great-circle distance across the date line as well as ordinary longitudes. */
    private fun distanceMeters(a: LocationFix, b: LocationFix): Double {
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val latA = Math.toRadians(a.latitude)
        val latB = Math.toRadians(b.latitude)
        val h = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(latA) * kotlin.math.cos(latB) * kotlin.math.sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
    }

    /**
     * The first usable fix is shared promptly. Subsequent fixes are stored after five minutes of credible
     * movement or 15 minutes while stationary/low accuracy. GPS jitter alone must not imply movement.
     * Android's provider scheduling and battery policy may delay either interval.
     */
    fun shouldRecord(last: LocationFix?, next: LocationFix): Boolean {
        if (last == null) return true
        val gap = locationInstantMs(next.at) - locationInstantMs(last.at)
        if (gap <= 0) return false
        val credibleMovement = last.accuracyM <= WEAK_ACCURACY_M && next.accuracyM <= WEAK_ACCURACY_M &&
            distanceMeters(last, next) > maxOf(MIN_MOVEMENT_M, last.accuracyM.toDouble() + next.accuracyM)
        return gap >= if (credibleMovement) MOVING_INTERVAL_MS else STATIONARY_INTERVAL_MS
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
