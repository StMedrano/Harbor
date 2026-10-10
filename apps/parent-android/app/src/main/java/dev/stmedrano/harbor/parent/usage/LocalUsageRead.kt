package dev.stmedrano.harbor.parent.usage

/** What the child's own screen can safely know. A lost checkpoint is recoverable (server checkpoint), never a crash. */
data class LocalUsageRead(val state: UsageStoredState?, val recovering: Boolean)

fun UsageStore.readForView(bindingId: String): LocalUsageRead =
    try { LocalUsageRead(read(bindingId), false) } catch (_: UsageCheckpointLost) { LocalUsageRead(null, true) }

/** Background work exists while sharing is on, or until an opt-out deletion has been confirmed by the server. */
val UsageStoredState.needsBackgroundWork: Boolean get() = consent || pending is PendingClear

/** One consistent child-side snapshot: local state plus the launchable inventory (only while sharing is on). */
data class LocalChildUsage(val read: LocalUsageRead, val inventory: InventoryResult?)
