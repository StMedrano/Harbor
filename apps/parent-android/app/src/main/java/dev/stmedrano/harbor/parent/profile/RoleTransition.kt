package dev.stmedrano.harbor.parent.profile

import dev.stmedrano.harbor.parent.child.ChildBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class RoleTransition(private val current: () -> ProfileLease?, private val binding: () -> ChildBinding?,
    private val transition: suspend (ProfileLease, suspend () -> Boolean) -> Boolean,
    private val parentCleanup: suspend () -> Boolean, private val eraseChild: suspend (ChildBinding) -> Unit) {
    suspend fun parentToSetup(): Boolean {
        val lease = current()?.takeIf { it.role == ProfileRole.PARENT } ?: return false
        return try { transition(lease) { parentCleanup() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
    suspend fun childToSetup(approval: ParentApproval): Boolean {
        var cleared = false
        try {
            val lease = current()?.takeIf { it.role == ProfileRole.CHILD } ?: return false
            val expected = binding()?.takeIf { it.deviceId == lease.ownerId } ?: return false
            if (!approval.authorize(expected) || current() != lease || binding() != expected) return false
            return transition(lease) {
                if (binding() != expected) false else {
                    approval.revoke(expected)
                    eraseChild(expected)
                    approval.clear()
                    cleared = true
                    true
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        finally {
            if (!cleared) withContext(NonCancellable) {
                withTimeoutOrNull(5000) {
                    try { approval.clear() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* The isolated adapter must always erase its local secrets in finally. */ }
                }
            }
        }
    }
}
