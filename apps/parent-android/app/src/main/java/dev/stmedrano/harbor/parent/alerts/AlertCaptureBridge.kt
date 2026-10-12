package dev.stmedrano.harbor.parent.alerts

/** Receives alerts the capture paths raise. The runtime (M5) installs the real sink; until then alerts are dropped safely. */
fun interface AlertSink { fun offer(alerts: List<MessageAlert>) }

/**
 * The single process-wide seam between the Android capture classes (notification listener, SMS receiver) and the
 * child alert runtime. The service and receiver own no logic and no text: they hand raw text to [core], then pass
 * the resulting text-free alerts to the installed [sink]. A null sink means sharing is not active for a child on
 * this phone, so nothing is kept.
 */
object AlertCaptureBridge {
    @Volatile private var sink: AlertSink? = null
    val core = AlertCaptureCore()
    val dedup = NotificationDedup()

    fun install(sink: AlertSink) { this.sink = sink }
    fun clear() { sink = null }

    fun submit(alerts: List<MessageAlert>) {
        if (alerts.isEmpty()) return
        sink?.offer(alerts)
    }
}
