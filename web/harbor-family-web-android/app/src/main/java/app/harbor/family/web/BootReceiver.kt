package app.harbor.family.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts location sharing after a reboot, but only if it was turned on and the phone is still paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val client = DeviceClient(context)
        if (client.isPaired() && client.locationEnabled && LocationService.hasLocationPermission(context)) {
            LocationService.start(context)
        }
    }
}
