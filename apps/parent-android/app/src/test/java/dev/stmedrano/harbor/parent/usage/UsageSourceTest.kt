package dev.stmedrano.harbor.parent.usage

import org.junit.Assert.*
import org.junit.Test

class UsageSourceTest {
    private val window=UsageWindow(0,60000,"UTC")
    @Test fun deniedAndLockedNeverReadPlatformEvents() {
        var reads=0
        val denied=AndroidUsageSource({false},{true}){reads++;emptyList()}
        assertEquals(UsageSourceResult.PermissionDenied,denied.read(window));assertEquals(0,reads)
        val locked=AndroidUsageSource({true},{false}){reads++;emptyList()}
        assertEquals(UsageSourceResult.UserLocked,locked.read(window));assertEquals(0,reads)
    }
    @Test fun emptyNullAndSecurityFailureRemainUnknown() {
        for(reader in listOf<(UsageWindow)->List<UsageEvent>?>( {emptyList()}, {null}, {throw SecurityException("denied")} )) {
            assertEquals(UsageSourceResult.Unavailable,AndroidUsageSource({true},{true},reader).read(window))
        }
    }
    @Test fun permissionLostDuringReadDiscardsEvents() {
        var permission=true
        val source=AndroidUsageSource({permission},{true}){permission=false;listOf(UsageEvent(0,UsageEventKind.SCREEN_OFF,null,null))}
        assertEquals(UsageSourceResult.PermissionDenied,source.read(window))
    }
    @Test fun grantedReadKeepsObservedEventsWithoutInventingState() {
        val events=listOf(UsageEvent(0,UsageEventKind.SCREEN_ON,null,null))
        assertEquals(UsageSourceResult.Observed(events),AndroidUsageSource({true},{true}){events}.read(window))
    }
    @Test fun launchableVisibleOnlyDeduplicatedAndDeterministic() {
        val candidates=listOf(AppCandidate("example.a","Zulu"),AppCandidate("example.a","Alpha"),AppCandidate("example.hidden","Hidden",visible=false),AppCandidate("example.service","Service",launchable=false))
        val r=AndroidAppInventory({candidates},{1234}).read() as InventoryResult.Observed
        assertEquals(listOf(UsageInventoryApp("example.a","Alpha")),r.apps);assertFalse(r.truncated);assertEquals(1234,r.capturedAtMs)
        assertEquals(r,AndroidAppInventory({candidates.reversed()},{1234}).read())
    }
    @Test fun largeInventoryIsExplicitlyTruncatedAndBounded() {
        val r=AndroidAppInventory({List(501){AppCandidate("example.app$it","App")}}, {1234}).read() as InventoryResult.Observed
        assertEquals(500,r.apps.size);assertTrue(r.truncated)
    }
    @Test fun labelsStayTextAndUnsafeLabelsFallBackToPackage() {
        val r=AndroidAppInventory({listOf(AppCandidate("example.html","<script>alert(1)</script>"),AppCandidate("example.bad","bad\u0085label"),AppCandidate("example.long","😀".repeat(201)))},{1234}).read() as InventoryResult.Observed
        assertEquals("<script>alert(1)</script>",r.apps.first{it.packageName=="example.html"}.label)
        assertEquals("example.bad",r.apps.first{it.packageName=="example.bad"}.label)
        assertEquals("example.long",r.apps.first{it.packageName=="example.long"}.label)
        assertEquals(InventoryResult.Unavailable,AndroidAppInventory({throw SecurityException("denied")},{1234}).read())
    }
    @Test fun publicClassCorrelationHasNoHiddenInstanceProofOrRawClassFields() {
        val mapper=UsageEventMapper()
        val a=mapper.map(0,UsageEventKind.RESUMED,"example.a","PrivateActivity")!!
        val b=mapper.map(1,UsageEventKind.PAUSED,"example.a","PrivateActivity")!!
        val c=mapper.map(2,UsageEventKind.RESUMED,"example.a","OtherActivity")!!
        assertEquals(a.instanceId,b.instanceId);assertNotEquals(a.instanceId,c.instanceId)
        assertFalse(a.identityKnown);assertFalse(a.toString().contains("PrivateActivity"))
    }
}
