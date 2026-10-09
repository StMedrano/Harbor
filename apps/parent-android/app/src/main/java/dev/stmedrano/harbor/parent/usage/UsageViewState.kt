package dev.stmedrano.harbor.parent.usage

/** Where a usage view came from. Child views are local; parent views are server-confirmed reads. */
enum class UsageViewOrigin { CHILD_PHONE, PARENT_READ }

/** Mutually exclusive top-level states. Unknown is never turned into zero. */
enum class UsageViewStatus { UNAVAILABLE, LOAD_FAILED, NO_REPORT, EXPIRED, ACCESS_LOST, SHARING_OFF, PERMISSION_REQUIRED, MEASURED }

/** foregroundMs == null means no foreground time was recorded for this app (not a measured zero). */
data class UsageAppRow(val packageName: String, val label: String?, val foregroundMs: Long?)
data class UsageDayRow(val localDate: String, val quality: UsageQuality, val totalMs: Long?, val coverageStart: String?)

data class UsageViewState(
    val status: UsageViewStatus,
    val origin: UsageViewOrigin,
    val observedAt: String? = null,
    val receivedAt: String? = null,
    val zoneId: String? = null,
    val stale: Boolean = false,
    val offline: Boolean = false,
    val uploadPending: Boolean = false,
    val collecting: Boolean = false,
    val inventory: InventoryStatus? = null,
    val days: List<UsageDayRow> = emptyList(),
    val apps: List<UsageAppRow> = emptyList(),
) {
    val today: UsageDayRow? get() = days.lastOrNull()

    companion object {
        const val STALE_AFTER_MS = 30L * 60 * 1000

        private fun isStale(receivedAt: String?, nowMs: Long): Boolean =
            receivedAt != null && nowMs - java.time.Instant.parse(receivedAt).toEpochMilli() > STALE_AFTER_MS

        private fun dayRows(days: List<UsageDay>) = days.sortedBy { it.localDate }
            .map { UsageDayRow(it.localDate, it.quality, it.totalMs, it.coverageStart) }

        private fun appRows(latest: UsageDay?, labels: Map<String, String?>): List<UsageAppRow> {
            val measured = latest?.apps.orEmpty().associate { it.packageName to it.foregroundMs }
            return (labels.keys + measured.keys).distinct()
                .map { UsageAppRow(it, labels[it]?.takeIf(String::isNotEmpty), measured[it]) }
                .sortedWith(compareByDescending<UsageAppRow> { it.foregroundMs ?: -1L }.thenBy { (it.label ?: it.packageName).lowercase() })
        }

        /** Parent read: only the server-confirmed reply; a null reply means reporting is unavailable here. */
        fun fromReply(reply: UsageReadReplyV1?, nowMs: Long, offline: Boolean = false): UsageViewState {
            val origin = UsageViewOrigin.PARENT_READ
            reply ?: return UsageViewState(UsageViewStatus.UNAVAILABLE, origin)
            return when (reply.state) {
                UsageReadState.NONE -> UsageViewState(UsageViewStatus.NO_REPORT, origin, offline = offline)
                UsageReadState.EXPIRED -> UsageViewState(UsageViewStatus.EXPIRED, origin, offline = offline)
                UsageReadState.AVAILABLE -> {
                    val report = reply.report
                    val received = reply.receivedAt
                    if (report == null || received == null) return UsageViewState(UsageViewStatus.UNAVAILABLE, origin)
                    val common = UsageViewState(UsageViewStatus.MEASURED, origin, report.observedAt, received, report.zoneId,
                        stale = isStale(received, nowMs), offline = offline, inventory = report.inventoryStatus)
                    if (report.usagePermission != UsagePermissionState.GRANTED) return common.copy(status = UsageViewStatus.PERMISSION_REQUIRED)
                    val days = report.days.sortedBy { it.localDate }
                    common.copy(days = dayRows(days), apps = appRows(days.lastOrNull(), report.inventory.associate { it.packageName to it.label }))
                }
            }
        }

        /** Child view of its own state; labels are resolved locally and never uploaded. */
        fun fromLocal(state: UsageStoredState?, runtime: UsageRuntimeStatus, permissionGranted: Boolean, nowMs: Long,
            label: (String) -> String?): UsageViewState {
            val origin = UsageViewOrigin.CHILD_PHONE
            if (state == null || !state.consent) return UsageViewState(UsageViewStatus.SHARING_OFF, origin)
            val received = state.receivedAt
            val base = UsageViewState(UsageViewStatus.NO_REPORT, origin, receivedAt = received, stale = isStale(received, nowMs),
                offline = runtime == UsageRuntimeStatus.OFFLINE, uploadPending = state.pending is PendingReport)
            if (!permissionGranted) return base.copy(status = UsageViewStatus.PERMISSION_REQUIRED)
            val aggregate = state.latestAggregate ?: return base.copy(collecting = true)
            val days = aggregate.days.sortedBy { it.localDate }
            val names = days.lastOrNull()?.apps.orEmpty().map { it.packageName }
            return base.copy(status = UsageViewStatus.MEASURED, observedAt = days.maxOfOrNull { it.observedThrough },
                zoneId = aggregate.window.zoneId, days = dayRows(days), apps = appRows(days.lastOrNull(), names.associateWith(label)))
        }
    }
}

fun formatUsageDuration(ms: Long?): String {
    if (ms == null || ms < 0) return "Unknown"
    val minutes = ms / 60000
    return if (minutes >= 60) "${minutes / 60}h ${(minutes % 60).toString().padStart(2, '0')}m" else "${minutes}m"
}

/** Plain-language notices, most important first. Never claims enforcement or Digital Wellbeing equivalence. */
fun usageNotices(state: UsageViewState): List<String> = buildList {
    when (state.status) {
        UsageViewStatus.UNAVAILABLE -> add("Usage reporting is not available here.")
        UsageViewStatus.LOAD_FAILED -> add("Couldn't load the report. Check your connection and refresh.")
        UsageViewStatus.NO_REPORT -> add(if (state.collecting) "Collecting. Nothing has been measured yet." else "No report yet.")
        UsageViewStatus.EXPIRED -> add("The last report expired. Reports older than 30 days are removed.")
        UsageViewStatus.ACCESS_LOST -> add("You no longer have access to this device's usage.")
        UsageViewStatus.SHARING_OFF -> add("Sharing is off. Nothing is measured or sent.")
        UsageViewStatus.PERMISSION_REQUIRED -> add("Usage Access is off. No screen time is measured, so totals are unknown, not zero.")
        UsageViewStatus.MEASURED -> {
            if (state.today?.quality == UsageQuality.PARTIAL) add("Partial day: measurement starts at ${state.today?.coverageStart ?: "an unknown time"}.")
            if (state.today?.quality == UsageQuality.UNAVAILABLE) add("Today was not measured.")
            if (state.inventory == InventoryStatus.TRUNCATED) add("The app list was truncated, so not every launchable app is shown.")
            if (state.inventory == InventoryStatus.UNAVAILABLE) add("The app list was not available.")
        }
    }
    if (state.stale) add("Out of date: the last upload was more than 30 minutes ago.")
    if (state.offline) add(if (state.origin == UsageViewOrigin.PARENT_READ) "Can't reach Harbor. Showing the last confirmed report." else "Offline. Reports upload when the phone reconnects.")
    if (state.uploadPending && !state.offline) add("Latest measurement is waiting to upload.")
}
