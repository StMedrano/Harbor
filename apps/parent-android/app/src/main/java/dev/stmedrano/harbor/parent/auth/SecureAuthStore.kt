package dev.stmedrano.harbor.parent.auth

import android.content.Context
import kotlinx.serialization.json.Json
import java.util.Base64

interface AuthValues {
    fun read(key: String): String?
    fun write(key: String, value: String?)
    fun clear()
}

interface AuthCipher {
    fun encrypt(slot: String, value: ByteArray): ByteArray
    fun decrypt(slot: String, value: ByteArray): ByteArray
}

class AuthStorageLost : IllegalStateException("Secure Auth storage unavailable; sign in again")

class SecureAuthStore(private val values: AuthValues, private val cipher: AuthCipher) {
    fun read(slot: String): String? {
        val encrypted = values.read(slot) ?: return null
        return try {
            cipher.decrypt(slot, Base64.getDecoder().decode(encrypted)).toString(Charsets.UTF_8)
        } catch (_: Exception) {
            values.clear()
            throw AuthStorageLost()
        }
    }

    fun write(slot: String, value: String?) {
        if (value == null) { values.write(slot, null); return }
        try {
            values.write(slot, Base64.getEncoder().encodeToString(cipher.encrypt(slot, value.toByteArray())))
        } catch (_: Exception) {
            values.clear()
            throw AuthStorageLost()
        }
    }

    var transaction: AuthTransaction?
        get() = read("transaction")?.let { encoded ->
            try { Json.decodeFromString<AuthTransaction>(encoded) }
            catch (_: Exception) { clear(); throw AuthStorageLost() }
        }
        set(value) = write("transaction", value?.let { Json.encodeToString(it) })

    fun clear() = values.clear()

    companion object {
        fun open(context: Context): SecureAuthStore {
            val prefs = context.applicationContext.getSharedPreferences("harbor-secure-auth", Context.MODE_PRIVATE)
            return SecureAuthStore(object : AuthValues {
                override fun read(key: String) = prefs.getString(key, null)
                override fun write(key: String, value: String?) {
                    check(prefs.edit().putString(key, value).commit()) { "Secure storage write failed" }
                }
                override fun clear() { check(prefs.edit().clear().commit()) { "Secure storage clear failed" } }
            }, KeystoreCipher())
        }
    }
}
