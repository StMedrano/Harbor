package dev.stmedrano.harbor.parent.access

import dev.stmedrano.harbor.parent.activity.BlockReason
import dev.stmedrano.harbor.parent.alerts.alertInstantMs
import dev.stmedrano.harbor.parent.controls.UninstallGuard
import dev.stmedrano.harbor.parent.controls.isPackageName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * A child's request for more time or access, sent from Harbor's block screen; mirrors
 * packages/contracts/src/v1/access-requests.ts. It names only the app and why it was blocked: there is no free text.
 */
@Serializable enum class AccessReason {
    @SerialName("paused") PAUSED, @SerialName("bedtime") BEDTIME, @SerialName("blocked_app") BLOCKED_APP,
    @SerialName("limit_reached") LIMIT_REACHED, @SerialName("time_up") TIME_UP;
    companion object {
        fun of(reason: BlockReason, daily: Boolean): AccessReason = when (reason) {
            BlockReason.PAUSED -> PAUSED
            BlockReason.BEDTIME -> BEDTIME
            BlockReason.BLOCKED_APP -> BLOCKED_APP
            BlockReason.LIMIT_REACHED -> if (daily) TIME_UP else LIMIT_REACHED
        }
    }
}
@Serializable data class AccessRequestV1(val version: Int, val requestId: String, val reason: AccessReason, val packageName: String, val appLabel: String)
@Serializable data class AccessRequestReplyV1(val requestId: String, val status: String, val requestedAt: String)
@Serializable data class AccessRequestItem(val requestId: String, val reason: AccessReason, val packageName: String, val appLabel: String,
    val requestedAt: String, val status: String, val decidedAt: String?)

/** An unanswered request stops counting after an hour; the child can ask again. */
const val ACCESS_REQUEST_TTL_MS = 60 * 60_000L
/** How long a parent's "yes" lets the app be used. */
const val ACCESS_GRANT_MS = 15 * 60_000L
private val statuses = setOf("pending", "approved", "denied", "expired")
private val json = Json { ignoreUnknownKeys = false; explicitNulls = true; encodeDefaults = true }
private val control = Regex("\\p{Cntrl}")

private fun uuid(value: String) = runCatching { UUID.fromString(value).toString() == value.lowercase() }.getOrDefault(false)
private fun validLabel(value: String) = value.isNotEmpty() && value.codePointCount(0, value.length) <= 64 && !control.containsMatchIn(value)
/** The label as the contract allows it: no control characters, 1–64 characters. */
fun accessLabel(value: String): String = value.replace(control, "").trim().take(64).ifEmpty { "This app" }

fun encodeAccessRequest(value: AccessRequestV1): String {
    require(value.version == 1 && uuid(value.requestId) && isPackageName(value.packageName) && validLabel(value.appLabel))
    return json.encodeToString(AccessRequestV1.serializer(), value)
}
fun decodeAccessRequestReply(raw: String): AccessRequestReplyV1 {
    val o = json.parseToJsonElement(raw).jsonObject
    require(o.keys == setOf("requestId", "status", "requestedAt"))
    val reply = json.decodeFromJsonElement(AccessRequestReplyV1.serializer(), o)
    require(uuid(reply.requestId) && reply.status in statuses + "limited")
    alertInstantMs(reply.requestedAt)
    return reply
}
fun decodeAccessRequests(raw: String): List<AccessRequestItem> {
    val root = json.parseToJsonElement(raw).jsonObject
    require(root.keys == setOf("requests"))
    val items = root.getValue("requests").jsonArray
    require(items.size <= 50)
    return items.map { element ->
        require(element.jsonObject.keys == setOf("requestId", "reason", "packageName", "appLabel", "requestedAt", "status", "decidedAt"))
        json.decodeFromJsonElement(AccessRequestItem.serializer(), element).also {
            require(uuid(it.requestId) && isPackageName(it.packageName) && it.status in statuses && validLabel(it.appLabel))
            alertInstantMs(it.requestedAt)
            if (it.status == "approved" || it.status == "denied") alertInstantMs(checkNotNull(it.decidedAt)) else require(it.decidedAt == null)
        }
    }
}

/** What the parent reads in the Requests list: what the child is asking for, in plain words. */
fun accessRequestTitle(item: AccessRequestItem): String = when (item.reason) {
    AccessReason.LIMIT_REACHED -> "15 more minutes of ${item.appLabel}"
    AccessReason.TIME_UP -> "15 more minutes of ${item.appLabel} (time's up today)"
    // Harbor can't tell which app the child wants to remove, so the parent is told it could be Harbor.
    AccessReason.BLOCKED_APP -> if (UninstallGuard.isRemoval(item.packageName, item.appLabel)) "Remove an app (it could be Harbor)"
        else "Use ${item.appLabel} (blocked)"
    AccessReason.PAUSED -> "Use ${item.appLabel} while the phone is paused"
    AccessReason.BEDTIME -> "Use ${item.appLabel} at bedtime"
}

/** The child's button on the block screen. */
fun askButtonText(reason: AccessReason, appLabel: String): String = when (reason) {
    AccessReason.LIMIT_REACHED, AccessReason.TIME_UP, AccessReason.BEDTIME -> "Ask for 15 more minutes"
    AccessReason.BLOCKED_APP, AccessReason.PAUSED -> "Ask to use $appLabel"
}
