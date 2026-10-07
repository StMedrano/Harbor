package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.auth.ParentAuthRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

interface FamilyChannel {
    val changed: Flow<Unit>
    val subscribed: Flow<Unit>
    suspend fun join()
    fun close()
}
fun interface FamilyChannelFactory { fun create(familyId: String): FamilyChannel }

class SdkFamilyChannelFactory(private val client: SupabaseClient, private val auth: ParentAuthRepository,
    private val scope: CoroutineScope) : FamilyChannelFactory {
    override fun create(familyId: String): FamilyChannel {
        require(ParentMessageParser.uuid(familyId))
        val sdk = client.channel("family:$familyId") { isPrivate = true }
        return object : FamilyChannel {
            private val closed = AtomicBoolean(false)
            override val changed = sdk.broadcastFlow("changed").map { Unit }
            override val subscribed = sdk.status.filter { it == RealtimeChannel.Status.SUBSCRIBED }.map { Unit }
            override suspend fun join() {
                check(!closed.get())
                auth.withAccessToken { token ->
                    check(!closed.get())
                    client.realtime.setAuth(token)
                    check(!closed.get())
                    sdk.subscribe()
                }
            }
            override fun close() {
                if (!closed.compareAndSet(false, true)) return
                // 3.8.0 marks channels UNSUBSCRIBED synchronously on disconnect.
                // Undispatched removal therefore clears the topic map before a
                // successor create, without waiting for a network unsubscribe.
                client.realtime.disconnect()
                scope.launch(start = CoroutineStart.UNDISPATCHED) { client.realtime.removeChannel(sdk) }
            }
        }
    }
}

class FamilyRealtime(private val factory: FamilyChannelFactory, private val scope: CoroutineScope,
    private val currentIdentity: () -> ParentIdentity?, private val currentFamily: () -> String?,
    private val refresh: suspend () -> Unit) {
    private data class Binding(val identity: ParentIdentity, val familyId: String, val generation: Long)
    private val lock = Any()
    private var generation = 0L
    private var binding: Binding? = null
    private var channel: FamilyChannel? = null
    private val jobs = mutableListOf<Job>()

    suspend fun connect(identity: ParentIdentity, familyId: String) {
        require(currentIdentity() == identity && currentFamily() == familyId) { "Current family context required" }
        val joined = synchronized(lock) {
            disconnectLocked()
            val active = factory.create(familyId)
            channel = active
            val ticket = Binding(identity, familyId, generation)
            binding = ticket
            jobs += scope.launch { active.changed.collect { refreshIfCurrent(ticket) } }
            jobs += scope.launch { active.subscribed.collect { refreshIfCurrent(ticket) } }
            scope.async { active.join() }.also { jobs += it }
        }
        joined.await()
    }
    fun disconnect() = synchronized(lock) { disconnectLocked() }
    private fun disconnectLocked() {
        generation++; binding = null
        jobs.forEach(Job::cancel); jobs.clear()
        channel?.close(); channel = null
    }
    suspend fun foreground() { val ticket = synchronized(lock) { binding } ?: return; refreshIfCurrent(ticket) }
    private suspend fun refreshIfCurrent(ticket: Binding) {
        if (!synchronized(lock) { binding == ticket && currentIdentity() == ticket.identity && currentFamily() == ticket.familyId }) return
        try { refresh() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Repository retains an explicit failed/stale view. */ }
    }
}
