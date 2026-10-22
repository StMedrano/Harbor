package dev.stmedrano.harbor.parent.access

import kotlinx.coroutines.CancellationException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class AskResult { SENT, LIMITED, FAILED }

/**
 * The child side of "ask a parent": sends one signed request from the block screen and remembers, for an hour, which
 * apps were already asked about so the screen shows "Asked" instead of offering the button again.
 */
class ChildAccessAsker(private val send: suspend (body: String) -> String, private val now: () -> Long) {
    private val asked = ConcurrentHashMap<String, Long>()
    fun askedRecently(packageName: String): Boolean = (asked[packageName] ?: Long.MIN_VALUE) > now() - ACCESS_REQUEST_TTL_MS
    suspend fun ask(reason: AccessReason, packageName: String, appLabel: String): AskResult = try {
        val body = encodeAccessRequest(AccessRequestV1(1, UUID.randomUUID().toString(), reason, packageName, accessLabel(appLabel)))
        when (decodeAccessRequestReply(send(body)).status) {
            "limited" -> AskResult.LIMITED
            else -> { asked[packageName] = now(); AskResult.SENT }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { AskResult.FAILED }
    /** The request for [packageName] was answered (a yes arrived, or the block ended): the child can ask again later. */
    fun forget(packageName: String) { asked.remove(packageName) }
    /** Any request was answered (yes or no push): the block screen offers asking again. */
    fun clear() = asked.clear()
}
