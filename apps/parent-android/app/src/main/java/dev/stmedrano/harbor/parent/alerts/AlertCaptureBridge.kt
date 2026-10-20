package dev.stmedrano.harbor.parent.alerts

/** Receives alerts the capture paths raise. The runtime installs the real sink. */
fun interface AlertSink { fun offer(alerts: List<MessageAlert>) }

/**
 * The single process-wide seam between the Android capture classes (notification listener, SMS receiver) and the
 * child alert runtime. The service and receiver own no logic and no text: they hand raw text to [core], then pass
 * the resulting text-free alerts to the installed [sink].
 *
 * Two things protect real alerts from being lost or doubled:
 * - A message that wakes a stopped app reaches the receiver before the runtime has installed its sink. Those alerts
 *   are held in memory for up to [HOLD_MS] (at most [HOLD_MAX]) and handed over on [install]. They carry no text and
 *   are dropped by [clear], so nothing is kept for a phone that is not a paired child.
 * - A text message arrives twice, once as an SMS broadcast and once as the Messages notification. The same app and
 *   category within [DUPLICATE_WINDOW_MS] counts as one alert.
 */
object AlertCaptureBridge {
    const val HOLD_MS = 120_000L
    const val HOLD_MAX = 50
    const val DUPLICATE_WINDOW_MS = 20_000L

    private val lock = Any()
    private var sink: AlertSink? = null
    private val held = ArrayDeque<Pair<Long, MessageAlert>>()
    private val recent = ArrayDeque<Triple<String, AlertCategory, Long>>()
    val core = AlertCaptureCore()
    val dedup = NotificationDedup()

    fun install(sink: AlertSink, nowMs: Long = System.currentTimeMillis()) {
        val replay = synchronized(lock) {
            this.sink = sink
            while (held.isNotEmpty() && nowMs - held.first().first > HOLD_MS) held.removeFirst()
            held.map { it.second }.also { held.clear() }
        }
        if (replay.isNotEmpty()) sink.offer(replay)
    }

    fun clear() = synchronized(lock) { sink = null; held.clear() }

    fun submit(alerts: List<MessageAlert>, nowMs: Long = System.currentTimeMillis()) {
        if (alerts.isEmpty()) return
        val (target, fresh) = synchronized(lock) {
            while (recent.isNotEmpty() && nowMs - recent.first().third > DUPLICATE_WINDOW_MS) recent.removeFirst()
            val kept = alerts.filter { a -> recent.none { it.first == a.appLabel && it.second == a.category } }
            kept.forEach { recent.addLast(Triple(it.appLabel, it.category, nowMs)) }
            if (sink == null) {
                kept.forEach { held.addLast(nowMs to it) }
                while (held.size > HOLD_MAX) held.removeFirst()
            }
            sink to kept
        }
        if (fresh.isNotEmpty()) target?.offer(fresh)
    }

    /** Test seam: forget everything, including the duplicate memory. */
    internal fun resetForTest() = synchronized(lock) { sink = null; held.clear(); recent.clear() }
}
