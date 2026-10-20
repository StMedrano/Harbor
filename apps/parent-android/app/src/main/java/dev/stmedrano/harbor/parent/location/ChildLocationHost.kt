package dev.stmedrano.harbor.parent.location

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

/** What the child sees on screen. `status == null` means location has not been evaluated yet. */
data class LocationHostState(val status: LocationStatus? = null, val uploadFailing: Boolean = false, val lastUploadMs: Long? = null)

/** The phone-facing pieces, as lambdas so the supervision rules can be tested without Android. */
class LocationPlatform(
    val access: () -> LocationAccess,
    val startUpdates: (onFix: (LocationFix) -> Unit) -> Boolean,
    val stopUpdates: () -> Unit,
    val lastKnown: () -> LocationFix?,
    /** Starts or stops the foreground service that keeps location running while the app is closed. Returns whether the request took effect. */
    val setService: (Boolean) -> Boolean,
)

/**
 * Keeps location sharing running for one child profile. There is deliberately no on/off switch: sharing follows the
 * phone's permission state, and a parent revoking the device ends it ([stop] and the store erase).
 */
class ChildLocationHost(
    private val scope: CoroutineScope,
    private val engine: LocationEngine,
    private val platform: LocationPlatform,
    private val current: (ProfileLease) -> Boolean,
    private val now: () -> Long,
) {
    private val control = Mutex()
    private val mutableState = MutableStateFlow(LocationHostState())
    val state: StateFlow<LocationHostState> = mutableState.asStateFlow()
    @Volatile private var active: ProfileLease? = null
    private var listening = false
    private var serviceOn = false
    @Volatile private var lastRecorded: LocationFix? = null
    private var lastFlushMs = 0L

    /** The newest place this phone already knows. Asking for it never starts location or takes a new reading. */
    fun latestFix(): LocationFix? = if (active != null) lastRecorded ?: platform.lastKnown() else null

    private fun valid(lease: ProfileLease) = active == lease && lease.role == ProfileRole.CHILD && current(lease)

    suspend fun start(lease: ProfileLease) = control.withLock {
        if (active == lease) return@withLock
        if (active != null) halt()
        if (lease.role == ProfileRole.CHILD) active = lease
        lastRecorded = null; lastFlushMs = 0L
        mutableState.value = LocationHostState()
    }

    suspend fun stop() = control.withLock { halt(); mutableState.value = LocationHostState() }

    private fun halt() {
        active = null
        listening = false
        platform.stopUpdates()
        serviceTo(false)
    }
    /** Only transitions reach the platform, because starting the service itself triggers a supervise pass. */
    private fun serviceTo(on: Boolean) {
        if (on == serviceOn) return
        if (platform.setService(on)) serviceOn = on
    }

    /**
     * Re-reads the phone's permission state, starts or stops location updates to match, and uploads. Safe to call often:
     * on foreground, on boot, after a permission dialog and every few minutes from the service.
     */
    suspend fun supervise(lease: ProfileLease) = control.withLock {
        if (!valid(lease)) return@withLock
        val access = platform.access()
        val status = locationStatus(access)
        if (locationServiceAllowed(access)) {
            if (!listening) {
                listening = platform.startUpdates { fix -> scope.launch { onFix(lease, fix) } }
                platform.lastKnown()?.let { fix -> acceptLocked(lease, fix) }
            }
            serviceTo(listening)
        } else {
            platform.stopUpdates(); listening = false
            serviceTo(false)
        }
        mutableState.value = mutableState.value.copy(status = status)
        uploadLocked(lease, status)
    }

    private suspend fun onFix(lease: ProfileLease, fix: LocationFix) = control.withLock {
        if (!valid(lease) || !listening) return@withLock
        acceptLocked(lease, fix)
        if (now() - lastFlushMs >= LocationCadence.FLUSH_INTERVAL_MS) uploadLocked(lease, mutableState.value.status ?: LocationStatus.SHARING)
    }

    private suspend fun acceptLocked(lease: ProfileLease, fix: LocationFix) {
        if (!LocationCadence.shouldRecord(lastRecorded, fix)) return
        try { engine.record(lease, fix); lastRecorded = fix }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A lost local copy is restored from the server checkpoint on the next upload. */ }
    }

    private suspend fun uploadLocked(lease: ProfileLease, status: LocationStatus) {
        lastFlushMs = now()
        try {
            engine.flush(lease, status)
            if (valid(lease)) mutableState.value = mutableState.value.copy(uploadFailing = false, lastUploadMs = now())
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { if (valid(lease)) mutableState.value = mutableState.value.copy(uploadFailing = true) }
    }
}
