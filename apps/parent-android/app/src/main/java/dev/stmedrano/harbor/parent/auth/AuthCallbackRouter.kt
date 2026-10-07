package dev.stmedrano.harbor.parent.auth

import java.net.URI
import java.net.URLDecoder

data class CallbackCode(val code: String, val transaction: AuthTransaction)

class AuthCallbackRouter(private val now: () -> Long = System::currentTimeMillis) {
    fun parse(uri: String, pending: AuthTransaction?): CallbackCode? {
        if (pending == null || pending.acceptedRecoverySubject != null ||
            now() - pending.startedAtMillis !in 0..900_000 || uri.length > 4096) return null
        return runCatching {
            val parsed = URI(uri)
            if (parsed.scheme != "harbor-parent" || parsed.rawAuthority != "auth" ||
                parsed.rawPath != "/callback" || parsed.rawFragment != null) return null
            val query = parsed.rawQuery ?: return null
            if (!query.startsWith("code=") || query.contains('&')) return null
            val code = URLDecoder.decode(query.substring(5), "UTF-8")
            if (code.isBlank() || code.any { it.isWhitespace() || it.isISOControl() }) return null
            CallbackCode(code, pending)
        }.getOrNull()
    }
}
