package dev.stmedrano.harbor.parent.alerts

import dev.stmedrano.harbor.parent.profile.ProfileLease
import dev.stmedrano.harbor.parent.profile.ProfileRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What the child sees for message alerts. `access == null` means nothing has been evaluated yet. */
data class AlertHostState(val access: AlertAccess? = null, val uploadFailing: Boolean = false, val lastUploadMs: Long? = null)

/**
 * Keeps message-alert sharing running for one child profile. There is no on/off switch: capture follows the phone's
 * Notification Access and SMS permission, and a parent revoking the device ends it ([stop] plus the store erase).
 * While active it installs an [AlertSink] into [AlertCaptureBridge]; the capture classes hand it text-free alerts,
 * which it records (deduped, bounded) and uploads. Alerts captured for any other profile are dropped by the lease fence.
 */
class ChildAlertHost(
    private val scope: CoroutineScope,
    private val reporter: AlertReporter,
    private val readAccess: () -> AlertAccess,
    private val current: (ProfileLease) -> Boolean,
    private val now: () -> Long,
) {
    private val control = Mutex()
    private val mutableState = MutableStateFlow(AlertHostState())
    val state: StateFlow<AlertHostState> = mutableState.asStateFlow()
    @Volatile private var active: ProfileLease? = null
    private var lastFlushMs = 0L

    private fun valid(lease: ProfileLease) = active == lease && lease.role == ProfileRole.CHILD && current(lease)

    suspend fun start(lease: ProfileLease) = control.withLock {
        if (active == lease) return@withLock
        if (active != null) halt()
        if (lease.role == ProfileRole.CHILD) {
            active = lease
            AlertCaptureBridge.install(AlertSink { alerts -> scope.launch { onAlerts(lease, alerts) } })
        }
        lastFlushMs = 0L
        mutableState.value = AlertHostState()
    }

    suspend fun stop() = control.withLock { halt(); mutableState.value = AlertHostState() }

    private fun halt() {
        active = null
        AlertCaptureBridge.clear()
    }

    /** Re-reads the two grants and uploads. Safe to call often: on foreground, on resume, and periodically. */
    suspend fun supervise(lease: ProfileLease) = control.withLock {
        if (!valid(lease)) return@withLock
        val access = readAccess()
        mutableState.value = mutableState.value.copy(access = access)
        uploadLocked(lease, access)
    }

    private suspend fun onAlerts(lease: ProfileLease, alerts: List<MessageAlert>) = control.withLock {
        if (!valid(lease)) return@withLock
        for (alert in alerts) {
            try { reporter.record(lease, alert) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A lost local copy is restored from the server checkpoint on the next upload. */ }
        }
        val access = mutableState.value.access ?: readAccess()
        if (now() - lastFlushMs >= FLUSH_INTERVAL_MS) uploadLocked(lease, access)
    }

    private suspend fun uploadLocked(lease: ProfileLease, access: AlertAccess) {
        lastFlushMs = now()
        try {
            reporter.flush(lease, access)
            if (valid(lease)) mutableState.value = mutableState.value.copy(uploadFailing = false, lastUploadMs = now())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (valid(lease)) mutableState.value = mutableState.value.copy(uploadFailing = true) }
    }

    companion object { const val FLUSH_INTERVAL_MS = 5_000L }
}
