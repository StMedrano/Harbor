package dev.stmedrano.harbor.parent.access

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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** The request was answered elsewhere or ran out before this answer arrived. */
class AccessRequestClosed : IllegalStateException("Access request already answered or expired")

interface ParentAccessApi {
    suspend fun read(deviceId: String): List<AccessRequestItem>
    suspend fun decide(requestId: String, approve: Boolean)
}

class SdkParentAccessApi(private val client: SupabaseClient, private val auth: ParentAuthRepository) : ParentAccessApi {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private fun requireUuid(value: String) = require(UUID.fromString(value).toString() == value.lowercase()) { "Invalid resource identifier" }
    override suspend fun read(deviceId: String): List<AccessRequestItem> = auth.withAccessToken {
        requireUuid(deviceId)
        try {
            val body = client.functions.invoke("get-access-requests", buildJsonObject { put("version", 1); put("deviceId", deviceId) },
                headers = jsonHeaders).bodyAsText()
            require(body.toByteArray(Charsets.UTF_8).size <= 65_536)
            decodeAccessRequests(body)
        } catch (failure: RestException) {
            if (failure.statusCode in setOf(403, 404)) throw FamilyAccessDenied()
            throw failure
        }
    }
    override suspend fun decide(requestId: String, approve: Boolean): Unit = auth.withAccessToken {
        requireUuid(requestId)
        val decision = if (approve) "approved" else "denied"
        try {
            val body = client.functions.invoke("decide-access-request",
                buildJsonObject { put("version", 1); put("requestId", requestId); put("decision", decision) }, headers = jsonHeaders).bodyAsText()
            check(Json.parseToJsonElement(body).jsonObject.getValue("status").jsonPrimitive.content == decision)
        } catch (failure: RestException) {
            // 403 here is a request this parent cannot see any more; 409 is one answered elsewhere or expired.
            if (failure.statusCode in setOf(403, 404, 409)) throw AccessRequestClosed()
            throw failure
        }
    }
}

enum class AccessViewStatus { AVAILABLE, ACCESS_LOST, LOAD_FAILED }
data class AccessViewState(
    val status: AccessViewStatus,
    val requests: List<AccessRequestItem> = emptyList(),
    /** The request being answered right now, so its buttons show progress and cannot be tapped twice. */
    val answering: String? = null,
    val problem: String? = null,
) {
    val pending: List<AccessRequestItem> get() = requests.filter { it.status == "pending" }
}

/**
 * Parent view of one phone's requests, fenced by identity, device and request order like the other parent models.
 * Saying yes first writes a 15-minute grant into the phone's controls (through the grant callback) and only then records
 * the answer, so the child is never told "yes" before the app actually opens.
 */
class ParentAccessModel(private val api: ParentAccessApi, private val currentIdentity: () -> ParentIdentity?) {
    data class Scope(val identity: ParentIdentity, val deviceId: String)
    data class Scoped(val scope: Scope, val view: AccessViewState)
    private val mutable = MutableStateFlow<Scoped?>(null)
    val state: StateFlow<Scoped?> = mutable.asStateFlow()
    private val generation = AtomicLong()
    private val pokes = MutableStateFlow(0)
    /** Bumped when a request push arrives, so an open Controls tab reloads at once. */
    val refreshes: StateFlow<Int> = pokes.asStateFlow()
    fun poke() { pokes.value++ }

    suspend fun load(identity: ParentIdentity, deviceId: String) {
        val scope = Scope(identity, deviceId)
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live()) return
        val previous = mutable.value?.takeIf { it.scope == scope }?.view
        try {
            val requests = api.read(deviceId)
            if (live()) mutable.value = Scoped(scope, AccessViewState(AccessViewStatus.AVAILABLE, requests,
                answering = previous?.answering, problem = previous?.problem))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: FamilyAccessDenied) { if (live()) mutable.value = Scoped(scope, AccessViewState(AccessViewStatus.ACCESS_LOST)) }
        catch (_: Exception) { if (live()) mutable.value = Scoped(scope, previous ?: AccessViewState(AccessViewStatus.LOAD_FAILED)) }
    }

    suspend fun answer(identity: ParentIdentity, deviceId: String, request: AccessRequestItem, approve: Boolean,
        grant: suspend (AccessRequestItem) -> Boolean): Boolean {
        val scope = Scope(identity, deviceId)
        val current = mutable.value?.takeIf { it.scope == scope && it.view.status == AccessViewStatus.AVAILABLE }?.view ?: return false
        if (current.answering != null || request.status != "pending" || currentIdentity() != identity) return false
        mutable.value = Scoped(scope, current.copy(answering = request.requestId, problem = null))
        val problem = try {
            if (approve && !grant(request)) "Harbor couldn't give the extra time. Check your connection and try again."
            else { api.decide(request.requestId, approve); null }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: AccessRequestClosed) { "That request was already answered or has expired." }
        catch (_: Exception) { "Your answer wasn't sent. Check your connection and try again." }
        mutable.value?.takeIf { it.scope == scope }?.let { mutable.value = Scoped(scope, it.view.copy(answering = null, problem = problem)) }
        load(identity, deviceId)
        return problem == null
    }

    fun clear() { generation.incrementAndGet(); mutable.value = null }
}
