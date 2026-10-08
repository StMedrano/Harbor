package dev.stmedrano.harbor.parent.notifications

import android.content.Context
import dev.stmedrano.harbor.parent.auth.AuthValues
import dev.stmedrano.harbor.parent.auth.ParentIdentity
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable data class RegistrationMarker(val userId: String, val sessionId: String, val registrationId: String)
@Serializable private data class OptInOwner(val userId: String, val sessionId: String)

// Only installation and verified binding references live here. Auth and Firebase
// tokens never enter this store; the installation identity survives sign-out.
class ParentRegistrationStore(private val values: AuthValues, private val newId: () -> String = { UUID.randomUUID().toString() }) {
    @Synchronized fun installationId(): String = values.read("installation") ?: newId().also { values.write("installation", it) }
    @Synchronized fun marker(identity: ParentIdentity): RegistrationMarker? {
        val encoded = values.read("marker") ?: return null
        return runCatching { Json.decodeFromString<RegistrationMarker>(encoded) }.getOrNull()?.takeIf {
            it.userId == identity.userId && it.sessionId == identity.sessionId && ParentMessageParser.uuid(it.registrationId)
        }
    }
    @Synchronized fun confirm(identity: ParentIdentity, registrationId: String) {
        require(ParentMessageParser.uuid(registrationId))
        values.write("marker", Json.encodeToString(RegistrationMarker(identity.userId, identity.sessionId, registrationId)))
    }
    @Synchronized fun clearMarker() = values.write("marker", null)
    @Synchronized fun setOptedIn(identity: ParentIdentity, enabled: Boolean) {
        values.write("opt-in", if (enabled) Json.encodeToString(OptInOwner(identity.userId, identity.sessionId)) else null)
    }
    @Synchronized fun optedIn(identity: ParentIdentity): Boolean = optedOwner() == identity
    @Synchronized fun optedOwner(): ParentIdentity? = values.read("opt-in")?.let {
        runCatching { Json.decodeFromString<OptInOwner>(it).let { owner -> ParentIdentity(owner.userId, owner.sessionId) } }.getOrNull()
    }
    @Synchronized fun clearOptIn() = values.write("opt-in", null)
    companion object {
        fun open(context: Context): ParentRegistrationStore {
            val prefs = context.applicationContext.getSharedPreferences("harbor-parent-registration", Context.MODE_PRIVATE)
            return ParentRegistrationStore(object : AuthValues {
                override fun read(key: String) = prefs.getString(key, null)
                override fun write(key: String, value: String?) { check(prefs.edit().putString(key, value).commit()) }
                override fun clear() { check(prefs.edit().clear().commit()) }
            })
        }
    }
}
