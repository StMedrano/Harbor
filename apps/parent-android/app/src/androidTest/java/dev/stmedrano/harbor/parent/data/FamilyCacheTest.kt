package dev.stmedrano.harbor.parent.data

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FamilyCacheTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun otherSubjectCannotReadCachedFamily() = runBlocking {
        val name = "family-cache-isolation-test"
        context.deleteDatabase(name)
        var database = ParentDatabase.open(context, name)
        try {
            database.familyCache().putSnapshot(FamilyCacheRow("parent-a", "family-a", "synthetic-public-snapshot", 1234))
            database.close()
            database = ParentDatabase.open(context, name)
            assertEquals("synthetic-public-snapshot", database.familyCache().getSnapshot("parent-a", "family-a")?.snapshotJson)
            assertNull(database.familyCache().getSnapshot("parent-b", "family-a"))
            assertNull(database.familyCache().getSnapshot("parent-a", "family-b"))
            database.familyCache().putSnapshot(FamilyCacheRow("parent-b", "family-a", "foreign-snapshot", 1234))
            database.familyCache().beginPending(PendingChildRow("parent-a", "family-a", "lost-response-key", "fingerprint", "Child"))
            database.familyCache().beginPending(PendingChildRow("parent-b", "family-a", "foreign-key", "fingerprint", "Other child"))
            database.familyCache().retainUser("parent-a")
            database.close()
            database = ParentDatabase.open(context, name)
            assertEquals("synthetic-public-snapshot", database.familyCache().getSnapshot("parent-a", "family-a")?.snapshotJson)
            assertEquals("lost-response-key", database.familyCache().getPending("parent-a", "family-a")?.idempotencyKey)
            assertNull(database.familyCache().getSnapshot("parent-b", "family-a"))
            assertNull(database.familyCache().getPending("parent-b", "family-a"))
        } finally { database.close(); context.deleteDatabase(name) }
    }

    @Test fun concurrentPendingOperationsKeepOneDurableKeyAcrossReopen() = runBlocking {
        val name = "family-pending-operation-test"
        context.deleteDatabase(name)
        var database = ParentDatabase.open(context, name)
        try {
            val dao = database.familyCache()
            val first = async { dao.beginPending(PendingChildRow("parent-a", "family-a", "first-key", "fingerprint", "Child")) }
            val second = async { dao.beginPending(PendingChildRow("parent-a", "family-a", "second-key", "fingerprint", "Child")) }
            val winner = first.await()
            assertEquals(winner, second.await())
            database.close()
            database = ParentDatabase.open(context, name)
            assertEquals(winner, database.familyCache().getPending("parent-a", "family-a"))
            assertNull(database.familyCache().getPending("parent-b", "family-a"))
            database.familyCache().deletePending("parent-a", "family-a", "unrelated-key")
            assertNotNull(database.familyCache().getPending("parent-a", "family-a"))
            database.familyCache().deletePending("parent-a", "family-a", winner.idempotencyKey)
            assertNull(database.familyCache().getPending("parent-a", "family-a"))
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
