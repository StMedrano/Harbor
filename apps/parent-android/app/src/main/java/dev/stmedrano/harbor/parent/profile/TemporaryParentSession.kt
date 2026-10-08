package dev.stmedrano.harbor.parent.profile

import android.content.Context
import dev.stmedrano.harbor.parent.BuildConfig
import dev.stmedrano.harbor.parent.auth.*
import dev.stmedrano.harbor.parent.child.ChildBinding
import dev.stmedrano.harbor.parent.family.SdkParentApi
import dev.stmedrano.harbor.parent.security.*
import java.util.concurrent.atomic.AtomicBoolean

/** This client never owns the primary parent session, cache, registry or dashboard. */
class TemporaryParentSession private constructor(val lease: ProfileLease, val binding: ChildBinding,
    val auth: ParentAuthRepository, private val mfa: MfaGateway, val approval: ScopedParentApproval,
    private val isCurrent: (ProfileLease) -> Boolean, private val erased: AtomicBoolean) {
    private var factor: String? = null
    val locallyCleared get() = erased.get()
    suspend fun signIn(email: String, password: String): Boolean {
        check(isCurrent(lease))
        erased.set(false)
        auth.signIn(email, password)
        check(isCurrent(lease))
        factor = mfa.listFactors().firstOrNull()
        return factor != null
    }
    suspend fun verify(code: String): Boolean {
        check(isCurrent(lease))
        approval.verify(checkNotNull(factor), code)
        return isCurrent(lease) && approval.authorize(binding)
    }
    suspend fun clear() { factor = null; approval.clear() }
    companion object {
        fun open(context: Context, lease: ProfileLease, binding: ChildBinding,
            isCurrent: (ProfileLease) -> Boolean): TemporaryParentSession {
            check(!BuildConfig.CI_FIXTURE) { "Live parent approval is unavailable in the offline preview" }
            check(lease.role == ProfileRole.CHILD && lease.ownerId == binding.deviceId && isCurrent(lease))
            val store = SecureAuthStore.openApproval(context)
            // A previous process's approval is never restored as authority.
            store.clear()
            val client = SupabaseAuthGateway.createClient(BuildConfig.SUPABASE_URL, BuildConfig.PUBLISHABLE_KEY, store)
            val auth = ParentAuthRepository(SupabaseAuthGateway(client, store), store)
            val mfa = SdkMfaGateway(client, auth)
            val erased = AtomicBoolean(true)
            val closed = AtomicBoolean(false)
            val approval = ScopedParentApproval(SdkParentApi(client, auth), mfa, { auth.identity.value },
                { auth.identity.value?.let { auth.signOutCurrent(it) } },
                { try { auth.clearLocal() } finally { store.clear() }
                    erased.set(true)
                    if (closed.compareAndSet(false, true)) client.close()
                })
            return TemporaryParentSession(lease, binding, auth, mfa, approval, isCurrent, erased)
        }
    }
}
