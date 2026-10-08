package app.harbor.family.web

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Foreground service that records this phone's location about every 5 minutes (and when it moves
 * 25 m or more) and uploads it, signed with the device key, to the report-location function.
 * The ongoing notification is deliberate: the child can always see that sharing is on.
 */
class LocationService : Service() {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var client: DeviceClient
    private lateinit var queue: LocationQueue
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private var updating = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) { result.locations.forEach(::record) }
    }

    override fun onCreate() {
        super.onCreate()
        client = DeviceClient(this)
        queue = LocationQueue(Vault(this))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must run promptly after startForegroundService, even if we then decide to stop.
        try { startInForeground() } catch (_: Exception) { stopSelf(); return START_NOT_STICKY }
        if (!client.isPaired() || !client.locationEnabled || !hasLocationPermission(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        if (!updating) {
            try {
                val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, FIVE_MINUTES)
                    .setMinUpdateIntervalMillis(60_000L)
                    .setMinUpdateDistanceMeters(25f)
                    .build()
                fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
                updating = true
                // First fix right away so parents don't wait five minutes after turning it on.
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, CancellationTokenSource().token)
                    .addOnSuccessListener { it?.let(::record) }
            } catch (_: SecurityException) {
                stopSelf(); return START_NOT_STICKY
            }
        }
        flush()
        return START_STICKY
    }

    override fun onDestroy() {
        if (updating) fused.removeLocationUpdates(callback)
        main.removeCallbacksAndMessages(null)
        io.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun record(loc: Location) {
        val now = System.currentTimeMillis()
        val point = JSONObject()
            .put("latitude", loc.latitude)
            .put("longitude", loc.longitude)
            .put("recordedAt", Instant.ofEpochMilli(if (loc.time in 1..now + 60_000) loc.time else now).toString())
        if (loc.hasAccuracy()) point.put("accuracyM", loc.accuracy.toDouble())
        battery()?.let { point.put("batteryPct", it) }
        io.execute { queue.add(point); uploadAll() }
    }

    private fun battery(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return pct.takeIf { it in 0..100 }
    }

    private fun flush() { io.execute { uploadAll() } }

    /** Sends queued points in batches of 50 until the queue is empty or an upload fails. */
    private fun uploadAll() {
        while (queue.size() > 0) {
            val batch = queue.peek(BATCH)
            when (client.reportLocations(batch)) {
                UploadResult.OK, UploadResult.DROP_BATCH -> queue.drop(batch.length())
                UploadResult.RETRY_LATER -> { main.postDelayed({ flush() }, RETRY_MS); return }
                UploadResult.REVOKED -> {
                    queue.clear(); client.clear()
                    main.post { stopSelf() }
                    return
                }
            }
        }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Location sharing", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while this phone shares its location with your parents"
                setShowBadge(false)
            },
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("Sharing location with your parents")
            .setContentText("Harbor Family is on. Open the app to turn this off.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL = "harbor_location"
        private const val NOTIFICATION_ID = 41
        private const val FIVE_MINUTES = 5 * 60_000L
        private const val RETRY_MS = 2 * 60_000L
        private const val BATCH = 50

        fun hasLocationPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun hasBackgroundPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < 29 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

        fun permissionLevel(context: Context): String = when {
            !hasLocationPermission(context) -> "none"
            hasBackgroundPermission(context) -> "background"
            else -> "foreground"
        }

        fun start(context: Context) {
            try { ContextCompat.startForegroundService(context, Intent(context, LocationService::class.java)) }
            catch (_: Exception) { /* not allowed right now (e.g. started from the background); retried when the app opens */ }
        }

        fun stop(context: Context) { context.stopService(Intent(context, LocationService::class.java)) }
    }
}
