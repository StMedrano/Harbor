package dev.stmedrano.harbor.parent.profile

import android.content.Context

interface ProfileStore {
    suspend fun read(): ProfileRole?
    suspend fun write(role: ProfileRole)
    suspend fun clear()
}

class AndroidProfileStore(context: Context, name: String = "harbor-family-profile") : ProfileStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override suspend fun read(): ProfileRole? = prefs.getString("role", null)?.let(ProfileRole::valueOf)
    override suspend fun write(role: ProfileRole) {
        check(prefs.edit().putString("role", role.name).commit()) { "Profile hint persistence unavailable" }
    }
    override suspend fun clear() {
        check(prefs.edit().clear().commit()) { "Profile hint cleanup unavailable" }
    }
}
