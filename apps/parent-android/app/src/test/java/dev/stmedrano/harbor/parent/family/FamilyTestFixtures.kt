package dev.stmedrano.harbor.parent.family

import dev.stmedrano.harbor.parent.data.*

internal class MemoryFamilyDao : FamilyCacheDao() {
    val snapshots = mutableMapOf<Pair<String, String>, FamilyCacheRow>()
    val operations = mutableMapOf<Pair<String, String>, PendingChildRow>()
    override suspend fun getSnapshot(userId: String, familyId: String) = snapshots[userId to familyId]
    override suspend fun putSnapshot(row: FamilyCacheRow) { snapshots[row.userId to row.familyId] = row }
    override suspend fun deleteFamily(userId: String, familyId: String) { snapshots.remove(userId to familyId) }
    override suspend fun clearAll() { snapshots.clear(); operations.clear() }
    override suspend fun deleteSnapshots() { snapshots.clear() }
    override suspend fun deleteOperations() { operations.clear() }
    override suspend fun deleteOtherSnapshots(userId: String) { snapshots.keys.removeAll { it.first != userId } }
    override suspend fun deleteOtherOperations(userId: String) { operations.keys.removeAll { it.first != userId } }
    override suspend fun getPending(userId: String, familyId: String) = operations[userId to familyId]
    override suspend fun insertPending(row: PendingChildRow) { operations.putIfAbsent(row.userId to row.familyId, row) }
    override suspend fun deletePending(userId: String, familyId: String, key: String) {
        if (operations[userId to familyId]?.idempotencyKey == key) operations.remove(userId to familyId)
    }
}

internal fun snapshot(user: String = "parent-a", family: String = "family-a", devices: List<DevicePublicV1> = emptyList()) = FamilySnapshot(
    family = FamilyV1(1, family, "Test family", "UTC", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
    membership = FamilyMemberV1(1, "membership", family, user, "owner", "active", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z"),
    children = listOf(ChildV1(1, "child-a", family, "Child", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")),
    devices = devices,
    fetchedAt = 1234,
)

internal open class TestParentApi : ParentApi {
    override suspend fun revokeDevice(familyId: String, deviceId: String): Unit = error("Unexpected revocation")
    override suspend fun listFamilies() = listOf(snapshot().family)
    override suspend fun createFamily(name: String, key: String) = CreatedFamily("family-a", name, "owner")
    override suspend fun createChild(request: CreateChildRequest) = snapshot().children.single().copy(displayName = request.displayName)
    override suspend fun createPairing(childId: String) = PairingCode("123456", "2026-01-01T00:10:00Z")
    override suspend fun readFamily(familyId: String) = snapshot(family = familyId)
}
