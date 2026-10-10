package dev.stmedrano.harbor.parent.usage

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.BuildConfig
import dev.stmedrano.harbor.parent.auth.EncryptedCodeVerifierCache
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import kotlinx.coroutines.runBlocking
import java.security.KeyStore
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class UsagePersistenceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val now=Instant.parse("2026-10-08T12:00:00Z").toEpochMilli()
    private val namespace="harbor-usage-cold-fixture"
    private fun store()=EncryptedUsageStore.open(context,namespace){now}
    private fun guard(){check(BuildConfig.CI_FIXTURE){"Offline usage fixture only"}}
    private fun sample()=decodeUsageReport(instrumentation.context.assets.open("usage-report-v1.json").bufferedReader().use{it.readText()},now)
    @Test fun seedUsageColdStart() = runBlocking {
        guard();val s=store();s.eraseBinding("fixture-binding")
        EncryptedCodeVerifierCache(SecureAuthStore.open(context)).saveCodeVerifier("usage-parent-fixture")
        s.update("fixture-binding"){it.copy(consent=true)}
        val p=s.newPendingReport("fixture-binding",sample())
        assertEquals(1L,p.report.sequence)
        val key=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}.getKey("$namespace-aes-v1",null)
        assertNotNull(key);assertNull(key.encoded)
        val raw=context.getSharedPreferences(namespace,Context.MODE_PRIVATE).getString("record",null)!!
        assertFalse(java.util.Base64.getDecoder().decode(raw).toString(Charsets.UTF_8).contains("example.test"))
    }
    @Test fun restoreUsageColdStartAndClearPreservesOtherNamespace() = runBlocking {
        guard();val s=store();val state=s.read("fixture-binding")!!
        assertEquals(2L,state.nextSequence);val p=state.pending as PendingReport
        assertEquals(sample().days,p.report.days)
        assertEquals(sample().days,decodeUsageReport(p.bodyUtf8,now).days)
        val clear=s.newPendingClear("fixture-binding");assertTrue(clear.clear.sequence>p.report.sequence)
        val reopened=store().read("fixture-binding")!!;assertEquals(clear,reopened.pending);assertNull(reopened.latestAggregate)
        assertEquals("usage-parent-fixture",EncryptedCodeVerifierCache(SecureAuthStore.open(context)).loadCodeVerifier())
        s.eraseBinding("fixture-binding");assertNull(s.read("fixture-binding"));SecureAuthStore.open(context).clear()
    }
    @Test fun usageKeyLossRetainsCheckpointAndDoesNotEraseParent() = runBlocking {
        guard();val s=store();s.eraseBinding("fixture-binding")
        EncryptedCodeVerifierCache(SecureAuthStore.open(context)).saveCodeVerifier("usage-parent-fixture")
        s.update("fixture-binding"){it.copy(consent=true)};s.newPendingReport("fixture-binding",sample())
        KeyStore.getInstance("AndroidKeyStore").apply{load(null);deleteEntry("$namespace-aes-v1")}
        assertThrows(UsageCheckpointLost::class.java){store().read("fixture-binding")}
        assertThrows(UsageCheckpointLost::class.java){store().newPendingReport("fixture-binding",sample())}
        assertEquals("usage-parent-fixture",EncryptedCodeVerifierCache(SecureAuthStore.open(context)).loadCodeVerifier())
        assertTrue(context.getSharedPreferences("$namespace-history",Context.MODE_PRIVATE).all.isNotEmpty())
        s.eraseBinding("fixture-binding");SecureAuthStore.open(context).clear()
    }
}
