package app.harbor.family.web

import java.net.HttpURLConnection
import java.net.URL

class HttpResult(val code: Int, val body: String)

/** Minimal JSON POST over HTTPS. IOException means the network failed (not the server). */
object Http {
    fun post(url: String, headers: Map<String, String>, body: String): HttpResult {
        require(url.startsWith("https://")) { "https only" }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.instanceFollowRedirects = false
            c.doOutput = true
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            return HttpResult(code, text)
        } finally {
            c.disconnect()
        }
    }
}
