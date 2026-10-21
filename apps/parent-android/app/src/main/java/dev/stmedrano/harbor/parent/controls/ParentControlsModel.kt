package dev.stmedrano.harbor.parent.controls

import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.functions.functions
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** A device's controls as the server holds them. `controls` is null until a parent sets any. */
data class DeviceControlsRead(val controls: ControlsV1?, val desiredStateVersion: Long, val acknowledgedVersion: Long)
/** Another parent (or this one, elsewhere) changed the controls first. */
class StaleControls : IllegalStateException("Controls changed elsewhere")

interface ParentControlsApi {
    suspend fun read(deviceId: String): DeviceControlsRead
    /** Writes `desiredState.controls` if the server is still at [expectedVersion]; returns the new version. */
    suspend fun write(familyId: String, deviceId: String, controls: ControlsV1, expectedVersion: Long): Long
}

fun decodeDeviceControlsRead(raw: String): DeviceControlsRead {
    val o = Json.parseToJsonElement(raw).jsonObject
    require(o.keys == setOf("controls", "desiredStateVersion", "acknowledgedVersion"))
    val controls = o.getValue("controls").takeUnless { it is JsonNull }?.let { decodeControls(it.toString()) }
    val desired = o.getValue("desiredStateVersion").jsonPrimitive.long
    val acked = o.getValue("acknowledgedVersion").jsonPrimitive.long
    require(desired >= 0 && acked in 0..desired)
    return DeviceControlsRead(controls, desired, acked)
}

class SdkParentControlsApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentControlsApi {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun requireUuid(value: String) = require(UUID.fromString(value).toString() == value.lowercase()) { "Invalid resource identifier" }
    private fun mapped(failure: RestException): Nothing {
        if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
        if (failure.statusCode == 409) throw StaleControls()
        throw failure
    }
    override suspend fun read(deviceId: String): DeviceControlsRead = auth.withAccessToken {
        requireUuid(deviceId)
        try {
            val body = client.functions.invoke("get-device-controls", buildJsonObject { put("version", 1); put("deviceId", deviceId) },
                headers = jsonHeaders).bodyAsText()
            require(body.toByteArray(Charsets.UTF_8).size <= 65_536)
            decodeDeviceControlsRead(body)
        } catch (failure: RestException) { mapped(failure) }
    }
    override suspend fun write(familyId: String, deviceId: String, controls: ControlsV1, expectedVersion: Long): Long = auth.withAccessToken {
        requireUuid(familyId); requireUuid(deviceId); require(expectedVersion >= 0)
        // Harbor's apps keep nothing else in a device's desired state, so the controls are the whole document.
        val payload = buildJsonObject {
            put("deviceId", deviceId); put("familyId", familyId); put("expectedVersion", expectedVersion)
            put("desiredState", buildJsonObject { put("controls", Json.parseToJsonElement(encodeControls(controls))) })
        }
        try {
            val body = client.functions.invoke("update-device-state", payload, headers = jsonHeaders).bodyAsText()
            Json.parseToJsonElement(body).jsonObject.getValue("desiredStateVersion").jsonPrimitive.long.also { require(it > expectedVersion) }
        } catch (failure: RestException) { mapped(failure) }
    }
}

enum class ControlsViewStatus { AVAILABLE, ACCESS_LOST, LOAD_FAILED }
data class ControlsViewState(
    val status: ControlsViewStatus,
    /** What the parent set; an empty policy when nothing is set. */
    val controls: ControlsV1 = ControlsV1(),
    val version: Long = 0,
    /** True when the phone has acknowledged this version, i.e. is enforcing it. */
    val applied: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
)

/**
 * Parent read and write of one device's controls. Every reply is fenced by the captured identity, device and request
 * order, so a late reply for another child or a signed-out parent is dropped. Each change is written as soon as the
 * parent makes it (like a switch, with no separate Save), one at a time and on top of the latest controls; a change that
 * loses a race reloads and re-applies itself once on top of the newer version.
 */
class ParentControlsModel(private val api: ParentControlsApi, private val currentIdentity: () -> ParentIdentity?) {
    data class Scope(val identity: ParentIdentity, val deviceId: String)
    data class Scoped(val scope: Scope, val view: ControlsViewState)
    private val mutable = MutableStateFlow<Scoped?>(null)
    val state: StateFlow<Scoped?> = mutable.asStateFlow()
    private val generation = AtomicLong()
    private val writes = Mutex()

    suspend fun load(identity: ParentIdentity, deviceId: String) {
        val scope = Scope(identity, deviceId)
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live() || writes.isLocked) return // A change being written shows its own result.
        val previous = mutable.value?.takeIf { it.scope == scope }?.view
        try {
            val read = api.read(deviceId)
            if (!live()) return
            mutable.value = Scoped(scope, view(read))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FamilyAccessDenied) { if (live()) mutable.value = Scoped(scope, ControlsViewState(ControlsViewStatus.ACCESS_LOST)) }
        catch (_: Exception) { if (live()) mutable.value = Scoped(scope, previous ?: ControlsViewState(ControlsViewStatus.LOAD_FAILED)) }
    }

    suspend fun save(identity: ParentIdentity, familyId: String, deviceId: String, controls: ControlsV1): Boolean =
        update(identity, familyId, deviceId) { controls }

    /** Applies [change] to the latest controls and writes the result now. The view shows it at once and undoes it on failure. */
    suspend fun update(identity: ParentIdentity, familyId: String, deviceId: String, change: (ControlsV1) -> ControlsV1): Boolean = writes.withLock {
        val scope = Scope(identity, deviceId)
        val current = mutable.value?.takeIf { it.scope == scope && it.view.status == ControlsViewStatus.AVAILABLE }?.view ?: return false
        val next = try { validateControls(change(current.controls)) } catch (_: Exception) { return false }
        if (next == current.controls) return true
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        mutable.value = Scoped(scope, current.copy(controls = next, applied = false, saving = true, saveFailed = false))
        return try {
            val (written, saved) = try { api.write(familyId, deviceId, next, current.version) to next }
            catch (_: StaleControls) {
                val fresh = api.read(deviceId)
                val again = validateControls(change(fresh.controls ?: ControlsV1()))
                api.write(familyId, deviceId, again, fresh.desiredStateVersion) to again
            }
            if (live()) mutable.value = Scoped(scope, ControlsViewState(ControlsViewStatus.AVAILABLE, saved, written, applied = false))
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FamilyAccessDenied) { if (live()) mutable.value = Scoped(scope, ControlsViewState(ControlsViewStatus.ACCESS_LOST)); false }
        catch (_: Exception) { if (live()) mutable.value = Scoped(scope, current.copy(saving = false, saveFailed = true)); false }
    }

    /** Sign-out, membership loss or role change: drop everything and invalidate in-flight work. */
    fun clear() { generation.incrementAndGet(); mutable.value = null }

    private fun view(read: DeviceControlsRead) = ControlsViewState(ControlsViewStatus.AVAILABLE, read.controls ?: ControlsV1(),
        read.desiredStateVersion, applied = read.acknowledgedVersion >= read.desiredStateVersion)
}
