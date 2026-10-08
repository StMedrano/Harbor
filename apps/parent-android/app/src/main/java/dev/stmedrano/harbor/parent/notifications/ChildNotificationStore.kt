package dev.stmedrano.harbor.parent.notifications

import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.child.ChildBinding
import android.content.Context
import kotlinx.serialization.json.Json

class ChildNotificationStore(private val values: AuthValues) {
    @Synchronized fun optedBinding(): ChildBinding? = values.read("opt-in")?.let { encoded ->
        runCatching { Json.decodeFromString<ChildBinding>(encoded) }.getOrNull()?.takeIf {
            listOf(it.deviceId, it.familyId, it.childId).all(ParentMessageParser::uuid)
        }
    }
    @Synchronized fun setOpted(binding: ChildBinding, enabled: Boolean) {
        require(listOf(binding.deviceId, binding.familyId, binding.childId).all(ParentMessageParser::uuid))
        if (enabled) values.write("opt-in", Json.encodeToString(binding))
        else if (optedBinding() == binding) values.write("opt-in", null)
    }
    companion object {
        fun open(context: Context): ChildNotificationStore {
            val prefs = context.applicationContext.getSharedPreferences("harbor-child-notifications", Context.MODE_PRIVATE)
            return ChildNotificationStore(object : AuthValues {
                override fun read(key: String) = prefs.getString(key, null)
                override fun write(key: String, value: String?) { check(prefs.edit().putString(key, value).commit()) }
                override fun clear() { check(prefs.edit().clear().commit()) }
            })
        }
    }
}
