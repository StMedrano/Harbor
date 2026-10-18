package dev.stmedrano.harbor.parent.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper

/** Reads this phone's permission state and delivers fixes from the platform LocationManager (no Google Play services). */
class AndroidLocationSource(context: Context) {
    private val context = context.applicationContext
    private val manager = this.context.getSystemService(LocationManager::class.java)
    private var listener: LocationListener? = null

    fun access(): LocationAccess {
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val background = granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val on = try { manager?.isLocationEnabled == true } catch (_: Exception) { false }
        return LocationAccess(fine, background, on)
    }
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    val listening: Boolean get() = listener != null

    /** Starts both providers. Missing permission or a disabled provider is not an error; it is reported through [access]. */
    @Suppress("MissingPermission")
    fun start(onFix: (LocationFix) -> Unit): Boolean {
        stop()
        val locations = manager ?: return false
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return false
        // Spelled out because Android 10-11 define the other callbacks as abstract; a lambda would crash there.
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) { convert(location)?.let(onFix) }
            @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        var any = false
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (provider in locations.allProviders) {
                    locations.requestLocationUpdates(provider, LocationCadence.REQUEST_INTERVAL_MS, LocationCadence.MIN_DISTANCE_M, callback, Looper.getMainLooper())
                    any = true
                }
            } catch (_: SecurityException) { /* Permission was withdrawn between the check and the request. */ }
            catch (_: IllegalArgumentException) { /* Provider disappeared. */ }
        }
        if (any) listener = callback else locations.removeUpdates(callback)
        return any
    }
    fun stop() {
        val active = listener ?: return
        listener = null
        try { manager?.removeUpdates(active) } catch (_: SecurityException) { /* Nothing left to remove. */ }
    }

    /** The newest cached fix, so a parent sees a place soon after sharing starts. */
    @Suppress("MissingPermission")
    fun lastKnown(): LocationFix? {
        val locations = manager ?: return null
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return null
        return try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { locations.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }?.let(::convert)
        } catch (_: SecurityException) { null }
    }

    private fun convert(location: Location): LocationFix? {
        if (isMock(location)) return null
        return toLocationFix(location.time, location.latitude, location.longitude, if (location.hasAccuracy()) location.accuracy else null, location.provider)
    }
    private fun isMock(location: Location) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
}
