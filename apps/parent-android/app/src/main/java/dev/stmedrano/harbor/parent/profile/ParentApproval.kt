package dev.stmedrano.harbor.parent.profile

import dev.stmedrano.harbor.parent.child.ChildBinding

interface ParentApproval {
    suspend fun authorize(binding: ChildBinding): Boolean
    suspend fun revoke(binding: ChildBinding)
    suspend fun clear()
}
