package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FamilyRealtimeTest {
    private class Channel : FamilyChannel {
        override val changed = MutableSharedFlow<Unit>()
        override val subscribed = MutableSharedFlow<Unit>()
        var joined = 0; var closed = 0
        override suspend fun join() { joined++ }
        override fun close() { closed++ }
    }
    @Test fun IdentityFamilySwitchStopsOldChannelAndReconnectForegroundRefreshAuthorizedState() = runTest {
        var identity = ParentIdentity("parent-a", "session-a")
        var family = FAMILY
        val channels = mutableListOf<Channel>()
        var refreshed = 0
        val realtime = FamilyRealtime(FamilyChannelFactory { Channel().also { channels += it } }, backgroundScope,
            { identity }, { family }, { refreshed++ })
        realtime.connect(identity, family); runCurrent()
        val first = channels.single()
        first.subscribed.emit(Unit); runCurrent()
        first.changed.emit(Unit); runCurrent()
        assertEquals(2, refreshed)
        identity = ParentIdentity("parent-b", "session-b"); family = DEVICE
        realtime.connect(identity, family); runCurrent()
        assertEquals(1, first.closed)
        first.changed.emit(Unit); runCurrent()
        assertEquals(2, refreshed)
        channels.last().subscribed.emit(Unit); runCurrent()
        assertEquals(3, refreshed)
        realtime.foreground(); assertEquals(4, refreshed)
        realtime.disconnect()
        channels.last().changed.emit(Unit); runCurrent()
        assertEquals(4, refreshed)
        assertTrue(channels.all { it.closed == 1 })
    }

    @Test fun ForeignContextCannotJoin() = runTest {
        val identity = ParentIdentity("parent-a", "session-a")
        var created = 0
        val realtime = FamilyRealtime(FamilyChannelFactory { created++; Channel() }, backgroundScope,
            { identity }, { FAMILY }, {})
        assertTrue(runCatching { realtime.connect(ParentIdentity("other", "other"), FAMILY) }.isFailure)
        assertTrue(runCatching { realtime.connect(identity, DEVICE) }.isFailure)
        assertEquals(0, created)
    }
}
