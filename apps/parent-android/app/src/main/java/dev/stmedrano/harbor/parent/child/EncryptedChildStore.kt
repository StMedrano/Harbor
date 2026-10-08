package dev.stmedrano.harbor.parent.child

import android.content.Context
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.auth.KeystoreCipher
import dev.stmedrano.harbor.parent.auth.SecureAuthStore
import kotlinx.serialization.json.Json
import java.security.KeyStore

class EncryptedChildStore(context: Context, private val name: String = "harbor-child-auth") : ChildStore {
    val keyAlias = "$name-aes-v1"
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    // Credential-free history survives a failed decrypt. It denies fresh setup;
    // it never authorizes a profile or supplies a bearer/binding.
    private val status = context.applicationContext.getSharedPreferences("$name-status", Context.MODE_PRIVATE)
    private val json = Json { encodeDefaults = true }
    private val secure = SecureAuthStore(object : AuthValues {
        override fun read(key: String) = prefs.getString(key, null)
        override fun write(key: String, value: String?) { check(prefs.edit().putString(key, value).commit()) }
        override fun clear() { check(prefs.edit().clear().commit()) }
    }, KeystoreCipher(keyAlias))
    override var claimPending: Boolean
        get() = status.getBoolean("pending", false)
        set(value) { check(status.edit().putBoolean("pending", value).commit()) }
    override val hasHistory get() = status.getBoolean("seen", false) || claimPending || prefs.contains("record")
    override fun load(): ChildRecord? = secure.read("record")?.let {
        try { json.decodeFromString<ChildRecord>(it) }
        catch (_: Exception) { throw IllegalStateException("Child storage unavailable") }
    }
    override fun save(record: ChildRecord) {
        check(record.session.anonymous) { "Anonymous child session required" }
        check(status.edit().putBoolean("seen", true).commit())
        secure.write("record", json.encodeToString(record))
    }
    override fun clear() {
        // Callers clear only after confirmed authorized cleanup or in isolated
        // offline fixture tests. Keep history until every secret/key is erased.
        secure.clear()
        KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(keyAlias) }
        check(status.edit().clear().commit())
    }
    companion object {
        fun hasRecords(context: Context): Boolean {
            val app = context.applicationContext
            return app.getSharedPreferences("harbor-child-auth", Context.MODE_PRIVATE).all.isNotEmpty() ||
                app.getSharedPreferences("harbor-child-auth-status", Context.MODE_PRIVATE).all.isNotEmpty()
        }
    }
}
