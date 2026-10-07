package dev.stmedrano.harbor.parent.data

import androidx.room.*

@Entity(tableName = "family_snapshots", primaryKeys = ["userId", "familyId"])
data class FamilyCacheRow(val userId: String, val familyId: String, val snapshotJson: String, val fetchedAt: Long)

@Entity(tableName = "pending_child_creation", primaryKeys = ["userId", "familyId"])
data class PendingChildRow(val userId: String, val familyId: String, val idempotencyKey: String, val fingerprint: String, val displayName: String)

@Dao
abstract class FamilyCacheDao {
    @Query("SELECT * FROM family_snapshots WHERE userId = :userId AND familyId = :familyId")
    abstract suspend fun getSnapshot(userId: String, familyId: String): FamilyCacheRow?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun putSnapshot(row: FamilyCacheRow)
    @Query("DELETE FROM family_snapshots WHERE userId = :userId AND familyId = :familyId")
    abstract suspend fun deleteFamily(userId: String, familyId: String)
    @Query("DELETE FROM family_snapshots")
    protected abstract suspend fun deleteSnapshots()
    @Query("DELETE FROM pending_child_creation")
    protected abstract suspend fun deleteOperations()
    @Transaction
    open suspend fun clearAll() { deleteSnapshots(); deleteOperations() }
    @Query("DELETE FROM family_snapshots WHERE userId != :userId")
    protected abstract suspend fun deleteOtherSnapshots(userId: String)
    @Query("DELETE FROM pending_child_creation WHERE userId != :userId")
    protected abstract suspend fun deleteOtherOperations(userId: String)
    @Transaction
    open suspend fun retainUser(userId: String) { deleteOtherSnapshots(userId); deleteOtherOperations(userId) }
    @Query("SELECT * FROM pending_child_creation WHERE userId = :userId AND familyId = :familyId")
    abstract suspend fun getPending(userId: String, familyId: String): PendingChildRow?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertPending(row: PendingChildRow)
    @Query("DELETE FROM pending_child_creation WHERE userId = :userId AND familyId = :familyId AND idempotencyKey = :key")
    abstract suspend fun deletePending(userId: String, familyId: String, key: String)
    @Transaction
    open suspend fun beginPending(row: PendingChildRow): PendingChildRow {
        insertPending(row)
        return checkNotNull(getPending(row.userId, row.familyId))
    }
}
