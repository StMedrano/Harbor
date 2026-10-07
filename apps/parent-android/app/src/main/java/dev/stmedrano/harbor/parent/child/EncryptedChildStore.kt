package dev.stmedrano.harbor.parent.child

import android.content.Context

class EncryptedChildStore(context: Context, private val name: String = "harbor-child-auth") : ChildStore {
    val keyAlias = "$name-aes-v1"
    override var claimPending: Boolean
        get() = false
        set(value) {}
    override val hasHistory = false
    override fun load(): ChildRecord? = null
    override fun save(record: ChildRecord) {}
    override fun clear() {}
}
