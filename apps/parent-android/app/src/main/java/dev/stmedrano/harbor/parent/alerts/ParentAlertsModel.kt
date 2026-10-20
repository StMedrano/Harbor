package dev.stmedrano.harbor.parent.alerts

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.family.FamilyAccessDenied
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

fun interface ParentAlertApi { suspend fun read(deviceId: String, before: String?, limit: Int): AlertReadReplyV1 }

enum class AlertsViewStatus { AVAILABLE, NONE, EXPIRED, ACCESS_LOST, LOAD_FAILED }

/** What a parent sees for message alerts. Built only from a server reply; `alerts` are newest first. */
data class AlertsViewState(
    val status: AlertsViewStatus,
    val notificationAccess: Boolean? = null,
    val smsPermission: Boolean? = null,
    val alerts: List<MessageAlert> = emptyList(),
    val receivedAtMs: Long? = null,
    val nextBefore: String? = null,
    /** True when this is the previous reply shown again because a refresh failed. */
    val offline: Boolean = false,
) {
    companion object {
        fun fromReply(reply: AlertReadReplyV1, offline: Boolean = false): AlertsViewState = when (reply.state) {
            AlertReadState.AVAILABLE -> AlertsViewState(AlertsViewStatus.AVAILABLE, reply.notificationAccess, reply.smsPermission,
                reply.alerts, reply.receivedAt?.let { java.time.Instant.parse(it).toEpochMilli() }, reply.nextBefore, offline)
            AlertReadState.NONE -> AlertsViewState(AlertsViewStatus.NONE, offline = offline)
            AlertReadState.EXPIRED -> AlertsViewState(AlertsViewStatus.EXPIRED, offline = offline)
        }
    }
}

/**
 * How a parent reads each category: a possibility, never a verdict. The app reports what a rule set flagged; it does
 * not claim to know what happened or that the child is unsafe or safe.
 */
fun alertCategoryLabel(category: AlertCategory): String = when (category) {
    AlertCategory.BULLYING -> "Possible bullying or harassment"
    AlertCategory.SELF_HARM -> "Possible self-harm or wellbeing concern"
    AlertCategory.ADULT_CONTENT -> "Possible adult content"
    AlertCategory.DRUGS_ALCOHOL -> "Possible drugs or alcohol"
    AlertCategory.VIOLENCE_THREAT -> "Possible violence or threat"
    AlertCategory.UNKNOWN_ADULT_CONTACT -> "Possible contact from an unknown adult"
}

/**
 * Extra guidance shown with an alert. Self-harm concerns carry supportive guidance and crisis resources rather than
 * only a label. Returns null when there is no special guidance for the category.
 */
fun alertGuidance(category: AlertCategory): String? = when (category) {
    AlertCategory.SELF_HARM ->
        "Harbor flagged wording that may relate to self-harm. It can be wrong, and it is not a diagnosis. If you are " +
            "worried about your child's safety, reach out to them and to professional support. In the US you can call " +
            "or text 988 (Suicide & Crisis Lifeline) any time, or text HOME to 741741 (Crisis Text Line). If someone is " +
            "in immediate danger, contact your local emergency number."
    else -> null
}

/** The standing limits notice shown on the parent alerts screen; it keeps the feature honest about what it can miss. */
const val ALERT_LIMITS_NOTICE =
    "Harbor only sees notifications it is allowed to read and the text of incoming SMS. Muted or hidden previews and " +
        "end-to-end encrypted messages can be missed, and the rules make mistakes in both directions. Harbor does not " +
        "read full conversations and never claims a message is safe."

/**
 * Memory-only, read-only parent alerts. Every reply is fenced by the captured identity, device and request order;
 * access loss replaces (never keeps) previous alerts, and nothing is persisted.
 */
class ParentAlertsModel(
    private val api: ParentAlertApi,
    private val currentIdentity: () -> ParentIdentity?,
    private val pageSize: Int = MAX_ALERT_PAGE,
) {
    data class Scope(val identity: ParentIdentity, val deviceId: String)
    data class Scoped(val scope: Scope, val view: AlertsViewState)
    private val mutable = MutableStateFlow<Scoped?>(null)
    val state: StateFlow<Scoped?> = mutable.asStateFlow()
    private val generation = AtomicLong()
    private var lastReply: Pair<Scope, AlertReadReplyV1>? = null

    suspend fun load(identity: ParentIdentity, deviceId: String) {
        val scope = Scope(identity, deviceId)
        val mine = generation.incrementAndGet()
        fun live() = mine == generation.get() && currentIdentity() == identity
        if (!live()) return
        val previous = lastReply?.takeIf { it.first == scope }?.second
        try {
            val reply = api.read(deviceId, before = null, limit = pageSize)
            if (!live()) return
            lastReply = scope to reply
            mutable.value = Scoped(scope, AlertsViewState.fromReply(reply))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: FamilyAccessDenied) {
            if (!live()) return
            lastReply = null
            mutable.value = Scoped(scope, AlertsViewState(AlertsViewStatus.ACCESS_LOST))
        } catch (_: Exception) {
            if (!live()) return
            mutable.value = Scoped(scope, if (previous != null) AlertsViewState.fromReply(previous, offline = true)
                else AlertsViewState(AlertsViewStatus.LOAD_FAILED))
        }
    }

    /** Sign-out, membership loss or role change: drop everything and invalidate in-flight reads. */
    fun clear() { generation.incrementAndGet(); lastReply = null; mutable.value = null }
}
