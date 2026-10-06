package dev.stmedrano.harbor.acceptance

import java.security.MessageDigest
import java.util.Locale

fun canonicalProof(method: String, operation: String, deviceId: String, body: ByteArray, timestamp: Long, nonce: String): ByteArray {
    require(listOf(method, operation, deviceId, nonce).all { it.isNotBlank() && !it.contains('\n') && !it.contains('\r') })
    val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it.toInt() and 255) }
    return listOf(method.uppercase(Locale.ROOT), operation, deviceId, hash, timestamp.toString(), nonce).joinToString("\n").toByteArray(Charsets.UTF_8)
}

fun derToP1363(signature: ByteArray): ByteArray {
    var offset = 0
    fun read(): Int { require(offset < signature.size) { "Truncated signature" }; return signature[offset++].toInt() and 255 }
    require(read() == 0x30)
    val length = read()
    require(length < 128 && length == signature.size - 2)
    fun integer(): ByteArray {
        require(read() == 0x02)
        val size = read()
        require(size in 1..33 && offset + size <= signature.size)
        val value = signature.copyOfRange(offset, offset + size)
        offset += size
        require((value[0].toInt() and 128) == 0) { "Negative scalar" }
        val scalar = if (value.size > 1 && value[0] == 0.toByte()) {
            require((value[1].toInt() and 128) != 0) { "Nonminimal integer" }
            value.copyOfRange(1, value.size)
        } else value
        require(scalar.size <= 32 && scalar.any { it != 0.toByte() })
        return ByteArray(32 - scalar.size) + scalar
    }
    val raw = integer() + integer()
    require(offset == signature.size) { "Trailing signature data" }
    return raw
}
