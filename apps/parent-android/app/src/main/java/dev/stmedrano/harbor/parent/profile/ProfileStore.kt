package dev.stmedrano.harbor.parent.profile

import android.content.Context

interface ProfileStore {
    suspend fun read(): ProfileRole?
    suspend fun write(role: ProfileRole)
    suspend fun clear()
}

class AndroidProfileStore(context: Context, name: String = "harbor-family-profile") : ProfileStore {
    private val prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    override suspend fun read(): ProfileRole? = null
    override suspend fun write(role: ProfileRole) { }
    override suspend fun clear() { }
}
