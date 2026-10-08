package app.harbor.family.web

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Prominent disclosure + runtime permission flow for location sharing (UI thread only).
 * 1. Plain-language disclosure the child must accept.  2. While-in-use location (and notifications).
 * 3. Optional "Allow all the time", which lets sharing restart by itself after a reboot.
 */
class LocationConsent(private val activity: AppCompatActivity, private val client: DeviceClient) {
    private var onDone: (() -> Unit)? = null
    private var awaitingSettings = false
    private var askedBackground = false

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterRequest()
    }

    fun start(done: () -> Unit) {
        onDone = done
        askedBackground = false
        if (LocationService.hasLocationPermission(activity) && client.locationEnabled) { enable(); return }
        AlertDialog.Builder(activity)
            .setTitle("Share this phone’s location?")
            .setMessage(DISCLOSURE)
            .setCancelable(false)
            .setPositiveButton("Agree and continue") { _, _ -> requestForeground() }
            .setNegativeButton("Not now") { _, _ -> finish() }
            .show()
    }

    fun disable() {
        client.locationEnabled = false
        LocationService.stop(activity)
        LocationQueue(Vault(activity)).clear()
    }

    /** Called from MainActivity.onResume: finishes the flow after the user comes back from system settings. */
    fun onResume() {
        if (awaitingSettings) { awaitingSettings = false; enable() }
    }

    private fun requestForeground() {
        if (LocationService.hasLocationPermission(activity)) { afterRequest(); return }
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        launcher.launch(perms.toTypedArray())
    }

    private fun afterRequest() {
        if (!LocationService.hasLocationPermission(activity)) { finish(); return }
        if (LocationService.hasBackgroundPermission(activity) || askedBackground) { enable(); return }
        askedBackground = true
        AlertDialog.Builder(activity)
            .setTitle("Keep sharing after a restart?")
            .setMessage(
                "To start sharing again by itself after this phone restarts, choose “Allow all the time” for location." +
                    " You can skip this: sharing still works while it is turned on.",
            )
            .setCancelable(false)
            .setPositiveButton("Choose") { _, _ ->
                if (Build.VERSION.SDK_INT >= 30) {
                    awaitingSettings = true
                    activity.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + activity.packageName)),
                    )
                } else {
                    launcher.launch(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
                }
            }
            .setNegativeButton("Skip") { _, _ -> enable() }
            .show()
    }

    private fun enable() {
        client.locationEnabled = true
        LocationService.start(activity)
        finish()
    }

    private fun finish() {
        val done = onDone
        onDone = null
        done?.invoke()
    }

    private companion object {
        const val DISCLOSURE =
            "Harbor Family will collect this phone’s location, including when the app is closed or not in use, " +
                "and send it to the parents in your family’s Harbor Family account.\n\n" +
                "It is collected about every 5 minutes and when the phone moves, along with the battery level. " +
                "Your parents see the latest location, and the last 7 days are kept.\n\n" +
                "A notice stays on screen while sharing is on, and you can turn it off in the app at any time."
    }
}
