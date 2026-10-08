package dev.stmedrano.harbor.parent.profile

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import org.junit.Assert.*
import org.junit.Test

class ParentApprovalIsolationTest {
    @Test fun temporaryApprovalErasurePreservesBothPrimaryNamespaces() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val parent = SecureAuthStore.open(context)
        val child = context.getSharedPreferences("harbor-child-auth", Context.MODE_PRIVATE)
        try {
            parent.write("approval-test-canary", "synthetic-parent-canary")
            assertTrue(child.edit().putString("approval-test-canary", "synthetic-child-canary").commit())
            val temporary = SecureAuthStore.openApproval(context)
            temporary.write("session", "synthetic-approval-session")
            temporary.write("code-verifier", "synthetic-approval-verifier")
            assertEquals("synthetic-approval-session", SecureAuthStore.openApproval(context).read("session"))
            val disk = context.getSharedPreferences("harbor-parent-approval", Context.MODE_PRIVATE)
            assertFalse(disk.all.values.any { it.toString().contains("synthetic-approval") })
            temporary.clear()
            assertNull(SecureAuthStore.openApproval(context).read("session"))
            assertNull(SecureAuthStore.openApproval(context).read("code-verifier"))
            assertEquals("synthetic-parent-canary", parent.read("approval-test-canary"))
            assertEquals("synthetic-child-canary", child.getString("approval-test-canary", null))
        } finally {
            parent.write("approval-test-canary", null)
            check(child.edit().remove("approval-test-canary").commit())
            context.getSharedPreferences("harbor-parent-approval", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }
}
