package dev.stmedrano.harbor.parent.profile

import dev.stmedrano.harbor.parent.auth.ParentIdentity
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.family.ParentApi
import dev.stmedrano.harbor.parent.security.MfaGateway
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class ScopedParentApproval(private val api: ParentApi, private val mfa: MfaGateway,
    private val identity: () -> ParentIdentity?, private val remoteLogout: suspend () -> Unit,
    private val localClear: suspend () -> Unit, private val now: () -> Long = System::currentTimeMillis) : ParentApproval {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    private var verifiedOwner: ParentIdentity? = null
    private var verifiedAt = 0L
    private var verifiedGeneration = -1L
    suspend fun verify(factorId: String, code: String) = mutex.withLock {
        verifiedOwner = null
        val captured = generation.get()
        val owner = checkNotNull(identity())
        mfa.challenge(factorId, code)
        check(identity() == owner && generation.get() == captured)
        verifiedOwner = owner; verifiedAt = now(); verifiedGeneration = captured
    }
    override suspend fun authorize(binding: ChildBinding) = mutex.withLock { matches(binding) }
    private suspend fun matches(binding: ChildBinding): Boolean {
        val owner = verifiedOwner ?: return false
        if (identity() != owner || verifiedGeneration != generation.get() || now() - verifiedAt !in 0..900000) return false
        val snapshot = api.readFamily(binding.familyId)
        return identity() == owner && verifiedGeneration == generation.get() && now() - verifiedAt in 0..900000 &&
            snapshot.family.id == binding.familyId && snapshot.membership.familyId == binding.familyId &&
            snapshot.membership.userId == owner.userId && snapshot.membership.status == "active" &&
            snapshot.membership.role in setOf("owner", "parent") &&
            snapshot.children.any { it.id == binding.childId && it.familyId == binding.familyId } &&
            snapshot.devices.any { it.id == binding.deviceId && it.familyId == binding.familyId &&
                it.childId == binding.childId && it.status == "active" }
    }
    override suspend fun revoke(binding: ChildBinding) = mutex.withLock {
        check(matches(binding)) { "Current family and fresh parent approval required" }
        // The server independently verifies membership and fresh MFA before confirming cleanup.
        api.revokeDevice(binding.familyId, binding.deviceId)
    }
    override suspend fun clear() {
        generation.incrementAndGet()
        mutex.withLock {
            verifiedOwner = null
            try { withTimeout(5000) { remoteLogout() } }
            finally { withContext(NonCancellable) { localClear() } }
        }
    }
}
