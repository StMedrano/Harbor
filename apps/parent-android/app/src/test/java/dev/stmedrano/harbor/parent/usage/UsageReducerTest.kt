package dev.stmedrano.harbor.parent.usage

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class UsageReducerTest {
    private val base=Instant.parse("2026-10-08T00:00:00Z").toEpochMilli()
    private fun e(ms:Long,kind:UsageEventKind,pkg:String?=null,id:Int?=1)=UsageEvent(base+ms,kind,pkg,id)
    private fun seed()=listOf(e(0,UsageEventKind.SCREEN_OFF),e(0,UsageEventKind.LOCKED),e(0,UsageEventKind.SCREEN_ON),e(0,UsageEventKind.UNLOCKED))
    private fun w(end:Long=90000)=UsageWindow(base,base+end,"UTC",setOf("example.home","com.android.systemui"))
    @Test fun unionDoesNotDoubleCount() {
        val r=reduceUsage(seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"),e(30000,UsageEventKind.RESUMED,"example.b"),e(60000,UsageEventKind.PAUSED,"example.a"),e(90000,UsageEventKind.STOPPED,"example.b")),w())
        val d=r.days.single();assertEquals(90000L,d.totalMs)
        assertEquals(mapOf("example.a" to 60000L,"example.b" to 60000L),d.apps.associate{it.packageName to it.foregroundMs})
        assertEquals(UsageQuality.OBSERVED,d.quality)
    }
    @Test fun distinctActivityInstancesPauseOnlyOne() {
        val r=reduceUsage(seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a",1),e(20000,UsageEventKind.RESUMED,"example.a",2),e(40000,UsageEventKind.PAUSED,"example.a",1),e(60000,UsageEventKind.STOPPED,"example.a",2)),w(80000))
        assertEquals(60000L,r.days.single().totalMs);assertEquals(60000L,r.days.single().apps.single().foregroundMs)
    }
    @Test fun duplicatesAndEventOrderDoNotAccumulate() {
        val events=seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"),e(60000,UsageEventKind.PAUSED,"example.a"))
        assertEquals(reduceUsage(events,w()),reduceUsage(events+events,w()))
        assertEquals(reduceUsage(events,w()),reduceUsage(events.take(4)+events.drop(4).reversed(),w()))
    }
    @Test fun launcherAndLockDoNotInflateUsage() {
        val r=reduceUsage(seed()+listOf(e(0,UsageEventKind.RESUMED,"example.home"),e(20000,UsageEventKind.PAUSED,"example.home"),e(20000,UsageEventKind.RESUMED,"example.a"),e(40000,UsageEventKind.LOCKED),e(60000,UsageEventKind.SCREEN_OFF)),w())
        assertEquals(20000L,r.days.single().totalMs);assertFalse(r.days.single().apps.any{it.packageName=="example.home"})
    }
    @Test fun unknownStartIsPartialNotZero() {
        val empty=reduceUsage(emptyList(),w());assertNull(empty.days.single().totalMs);assertEquals(UsageQuality.UNAVAILABLE,empty.days.single().quality)
        val r=reduceUsage(listOf(e(10000,UsageEventKind.SCREEN_ON),e(10000,UsageEventKind.UNLOCKED),e(10000,UsageEventKind.RESUMED,"example.a"),e(60000,UsageEventKind.PAUSED,"example.a")),w())
        assertEquals(50000L,r.days.single().totalMs);assertEquals(UsageQuality.PARTIAL,r.days.single().quality)
    }
    @Test fun midnightSplitsCorrectly() {
        val start=base+86370000;val events=seed().map{it.copy(atMs=start)}+listOf(UsageEvent(start,UsageEventKind.RESUMED,"example.a",1))
        val r=reduceUsage(events,UsageWindow(start,start+60000,"UTC"))
        assertEquals(listOf(30000L,30000L),r.days.map{it.totalMs})
    }
    @Test fun dstUsesElapsedBounds() {
        val start=Instant.parse("2026-03-08T05:00:00Z").toEpochMilli();val end=Instant.parse("2026-03-09T04:00:00Z").toEpochMilli()
        val events=seed().map{it.copy(atMs=start)}+listOf(UsageEvent(start,UsageEventKind.RESUMED,"example.a",1))
        val d=reduceUsage(events,UsageWindow(start,end,"America/New_York")).days.first()
        assertEquals(82800000L,d.totalMs);assertEquals(end-start,Instant.parse(d.endAt).toEpochMilli()-Instant.parse(d.startAt).toEpochMilli())
    }
    @Test fun gracefulShutdownCreditsTheTimeTheDeviceWasOn() {
        val r=reduceUsage(seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"),e(30000,UsageEventKind.SHUTDOWN)),w())
        assertEquals(30000L,r.days.single().totalMs);assertEquals(UsageQuality.PARTIAL,r.days.single().quality)
    }
    @Test fun bootOrClockJumpNeverCarriesAnOpenIntervalThroughTheUnknownGap() {
        // No shutdown was logged (crash, battery death, clock change): the time before the event is unknown, not app time.
        for(kind in listOf(UsageEventKind.STARTUP,UsageEventKind.CLOCK_GAP)) {
            val r=reduceUsage(seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"),e(50_400_000,kind)),w(51_000_000))
            val day=r.days.single()
            assertNull("$kind",day.totalMs);assertEquals(UsageQuality.UNAVAILABLE,day.quality);assertTrue(day.apps.isEmpty())
        }
    }
    @Test fun measurementResumesOnlyFromKnownStateAfterABoot() {
        val after=listOf(e(50_400_000,UsageEventKind.STARTUP),e(50_500_000,UsageEventKind.SCREEN_ON),e(50_500_000,UsageEventKind.UNLOCKED),
            e(50_500_000,UsageEventKind.RESUMED,"example.b"),e(50_560_000,UsageEventKind.PAUSED,"example.b"))
        val day=reduceUsage(seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"))+after,w(51_000_000)).days.single()
        assertEquals(60_000L,day.totalMs);assertEquals(mapOf("example.b" to 60_000L),day.apps.associate{it.packageName to it.foregroundMs})
        assertEquals(UsageQuality.PARTIAL,day.quality)
    }
    @Test fun repeatWindowDoesNotAccumulateAndRetainsOlderObservedRanges() {
        val events=seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"),e(60000,UsageEventKind.PAUSED,"example.a"))
        val r=reduceUsage(events,w());assertEquals(r,reconcileUsage(r,events,w()))
        assertEquals(60000L,reconcileUsage(r,emptyList(),w()).days.single().totalMs)
        val replacement=seed()+listOf(e(0,UsageEventKind.RESUMED,"example.b"),e(30000,UsageEventKind.PAUSED,"example.b"))
        val updated=reconcileUsage(r,replacement,w());assertEquals(30000L,updated.days.single().totalMs);assertEquals("example.b",updated.days.single().apps.single().packageName)
    }
    @Test fun timezoneStartsPartialEpoch() {
        val events=seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"))
        val r=reduceUsage(events,w());val next=reconcileUsage(r,events,w().copy(zoneId="America/Chicago"))
        assertEquals("America/Chicago",next.window.zoneId);assertEquals(90000L,next.days.single().totalMs);assertEquals(UsageQuality.PARTIAL,next.days.single().quality)
    }
    @Test fun partialReplacementRetainsOlderObservationsAndPreWindowState() {
        val previous=reduceUsage(seed()+listOf(e(0,UsageEventKind.RESUMED,"example.a"),e(60000,UsageEventKind.PAUSED,"example.a")),w())
        val later=seed().map{it.copy(atMs=base+30000)}+listOf(e(30000,UsageEventKind.RESUMED,"example.b"),e(60000,UsageEventKind.PAUSED,"example.b"))
        val result=reconcileUsage(previous,later,w(120000)).days.single()
        assertEquals(60000L,result.totalMs);assertEquals(mapOf("example.a" to 30000L,"example.b" to 30000L),result.apps.associate{it.packageName to it.foregroundMs})
        val before=seed().map{it.copy(atMs=base-60000)}+listOf(e(-30000,UsageEventKind.RESUMED,"example.a"),e(30000,UsageEventKind.PAUSED,"example.a"))
        assertEquals(30000L,reduceUsage(before,w()).days.single().totalMs)
    }
    @Test fun manyAppsAndAggregateSegmentsStayBoundedAndExplicitlyPartial() {
        val apps=seed()+List(501){e(0,UsageEventKind.RESUMED,"example.app$it")}
        val d=reduceUsage(apps,w()).days.single();assertEquals(500,d.apps.size);assertEquals(UsageQuality.PARTIAL,d.quality);assertEquals(90000L,d.totalMs)
    }
    @Test fun aggregateSegmentsStayBoundedAndExplicitlyPartial() {
        val toggles=seed()+List(20002){i->e(i.toLong(),if(i%2==0)UsageEventKind.RESUMED else UsageEventKind.PAUSED,"example.a")}
        val r=reduceUsage(toggles,w(21000));assertTrue(r.coverage.slices.size<=20000);assertEquals(UsageQuality.PARTIAL,r.days.single().quality)
    }}
