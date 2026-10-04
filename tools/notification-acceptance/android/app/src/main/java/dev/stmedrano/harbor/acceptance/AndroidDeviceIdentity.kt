package dev.stmedrano.harbor.acceptance

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class AndroidDeviceKey {
    private val alias = "harbor.acceptance.device.p256"
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    @Synchronized fun publicKeySpki(): String {
        if (!store().containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build())
            generator.generateKeyPair()
        }
        return Base64.getEncoder().encodeToString(store().getCertificate(alias).publicKey.encoded)
    }
    @Synchronized fun sign(bytes: ByteArray): ByteArray {
        check(store().containsAlias(alias)) { "Enroll the device key first" }
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(store().getKey(alias, null) as java.security.PrivateKey)
        signature.update(bytes)
        return derToP1363(signature.sign())
    }
}

class AndroidChildStore(context: Context) {
    private val preferences = context.getSharedPreferences("harbor.acceptance.child", Context.MODE_PRIVATE)
    fun saveSession(session: ChildSession) {
        require(session.anonymous)
        val json = JSONObject().put("accessToken", session.accessToken).put("refreshToken", session.refreshToken)
            .put("expiresAt", session.expiresAt).put("anonymous", true)
        check(preferences.edit().putString("session", json.toString()).commit()) { "Child session persistence failed" }
    }
    fun loadSession(): ChildSession? = preferences.getString("session", null)?.let {
        val json = JSONObject(it)
        require(json.getBoolean("anonymous"))
        ChildSession(json.getString("accessToken"), json.getString("refreshToken"), json.getLong("expiresAt"), true)
    }
    fun saveBinding(binding: DeviceBinding) {
        val json = JSONObject().put("deviceId", binding.deviceId).put("familyId", binding.familyId).put("childId", binding.childId)
        check(preferences.edit().putString("binding", json.toString()).commit()) { "Device binding persistence failed" }
    }
    fun loadBinding(): DeviceBinding? = preferences.getString("binding", null)?.let {
        val json = JSONObject(it)
        DeviceBinding(json.getString("deviceId"), json.getString("familyId"), json.getString("childId"))
    }
}

fun androidTransport(request: HarborRequest): HarborReply {
    check(!BuildConfig.CI_FIXTURE) { "CI fixture APK cannot call the hosted development backend" }
    val uri = URI(request.url)
    require(uri.scheme == "https" && uri.host == "bfvybxkjxilntjgndsrm.supabase.co" && uri.port == -1)
    val connection = uri.toURL().openConnection() as HttpURLConnection
    try {
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 20000
        connection.readTimeout = 20000
        connection.requestMethod = request.method
        request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
        connection.doOutput = true
        connection.outputStream.use { it.write(request.body) }
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val bytes = input?.use {
            val result = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                check(result.size() + count <= 1024 * 1024) { "Backend response too large" }
                result.write(buffer, 0, count)
            }
            result.toByteArray()
        } ?: byteArrayOf()
        return HarborReply(status, bytes.toString(Charsets.UTF_8))
    } finally { connection.disconnect() }
}
