package dev.stmedrano.harbor.parent.activity

import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import dev.stmedrano.harbor.parent.alerts.alertInstantString
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

fun interface ParentActivityApi { suspend fun read(deviceId: String, from: String, to: String): ActivityReadReplyV1 }

class SdkParentActivityApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentActivityApi {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    override suspend fun read(deviceId: String, from: String, to: String): ActivityReadReplyV1 = auth.withAccessToken {
        require(UUID.fromString(deviceId).toString() == deviceId.lowercase()) { "Invalid resource identifier" }
        try {
            val payload = buildJsonObject { put("version", 1); put("deviceId", deviceId); put("from", from); put("to", to) }
            val body = client.functions.invoke("get-device-activity", payload, headers = jsonHeaders).bodyAsText()
            require(body.toByteArray(Charsets.UTF_8).size <= 600_000)
            decodeActivityRead(body)
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
}

enum class ActivityViewStatus { AVAILABLE, NONE, EXPIRED, ACCESS_LOST, LOAD_FAILED }
data class AppTotal(val packageName: String, val label: String, val ms: Long)
data class ActivityViewState(
    val status: ActivityViewStatus,
    val day: LocalDate,
    val controlsOn: Boolean? = null,
    val events: List<ActivityEvent> = emptyList(),
    val totals: List<AppTotal> = emptyList(),
    val truncated: Boolean = false,
    val receivedAtMs: Long? = null,
    val offline: Boolean = false,
)

/** The [day] in the parent's zone, as an instant window the server accepts (at most 25 hours, across a DST change). */
fun dayWindow(day: LocalDate, zone: ZoneId): Pair<String, String> =
    alertInstantString(day.atStartOfDay(zone).toInstant().toEpochMilli()) to alertInstantString(day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())

/** Time per app inside the window, from session events (a session crossing midnight counts only its part in the day). */
fun appTotals(events: List<ActivityEvent>, fromMs: Long, toMs: Long): List<AppTotal> = events
    .filter { it.kind == ActivityKind.APP_SESSION && it.packageName != null && it.endAt != null }
    .groupBy { it.packageName!! }
    .map { (pkg, sessions) ->
        AppTotal(pkg, sessions.first().appLabel ?: pkg, sessions.sumOf { s ->
            maxOf(0L, minOf(alertInstantMs(s.endAt!!), toMs) - maxOf(alertInstantMs(s.at), fromMs))
        })
    }
    .filter { it.ms > 0 }
    .sortedByDescending { it.ms }

/** One timeline line in plain words. */
fun activityLine(e: ActivityEvent): String {
    val app = e.appLabel ?: "An app"
    return when (e.kind) {
        ActivityKind.APP_SESSION -> {
            val minutes = maxOf(1L, (alertInstantMs(e.endAt!!) - alertInstantMs(e.at)) / 60_000L)
            "$app · $minutes min"
        }
        ActivityKind.BLOCKED_OPEN -> when (e.reason) {
            BlockReason.PAUSED -> "Tried to open $app while the phone was paused"
            BlockReason.BEDTIME -> "Tried to open $app during bedtime"
            BlockReason.LIMIT_REACHED -> "Tried to open $app after its time ran out"
            else -> "Tried to open $app (blocked)"
        }
        ActivityKind.LIMIT_REACHED -> "$app daily time limit reached"
        ActivityKind.PAUSE_STARTED -> "Phone paused"
        ActivityKind.PAUSE_ENDED -> "Pause ended"
        ActivityKind.BEDTIME_STARTED -> "Bedtime started"
        ActivityKind.BEDTIME_ENDED -> "Bedtime ended"
        ActivityKind.PROTECTION_OFF -> "App controls were turned off on the phone"
        ActivityKind.PROTECTION_ON -> "App controls were turned back on"
    }
}

/**
 * Memory-only, read-only parent activity for one day. Every reply is fenced by the captured identity, device, day and
 * request order; access loss replaces (never keeps) previous data, and nothing is persisted.
 */
class ParentActivityModel(private val api: ParentActivityApi, private val currentIdentity: () -> ParentIdentity?,
    private val zone: () -> ZoneId = ZoneId::systemDefault) {
    data class Scope(val identity: ParentIdentity, val deviceId: String, val day: LocalDate)
    data class Scoped(val scope: Scope, val view: ActivityViewState)
    private val mutable = MutableStateFlow<Scoped?>(null)
    val state: StateFlow<Scoped?> = mutable.asStateFlow()
    private val generation = AtomicLong()

    suspend fun load(identity: ParentIdentity, deviceId: String, day: LocalDate) {
        val scope = Scope(identity, deviceId, day)
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live()) return
        val previous = mutable.value?.takeIf { it.scope == scope }?.view
        val (from, to) = dayWindow(day, zone())
        try {
            val reply = api.read(deviceId, from, to)
            if (!live()) return
            mutable.value = Scoped(scope, when (reply.state) {
                ActivityReadState.AVAILABLE -> ActivityViewState(ActivityViewStatus.AVAILABLE, day, reply.controlsOn, reply.events,
                    appTotals(reply.events, alertInstantMs(from), alertInstantMs(to)), reply.truncated, reply.receivedAt?.let(::alertInstantMs))
                ActivityReadState.NONE -> ActivityViewState(ActivityViewStatus.NONE, day)
                ActivityReadState.EXPIRED -> ActivityViewState(ActivityViewStatus.EXPIRED, day)
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FamilyAccessDenied) { if (live()) mutable.value = Scoped(scope, ActivityViewState(ActivityViewStatus.ACCESS_LOST, day)) }
        catch (_: Exception) { if (live()) mutable.value = Scoped(scope, previous?.copy(offline = true) ?: ActivityViewState(ActivityViewStatus.LOAD_FAILED, day)) }
    }

    fun clear() { generation.incrementAndGet(); mutable.value = null }
}
