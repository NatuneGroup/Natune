package moe.rukamori.archivetune.shared.data.provider

import java.security.MessageDigest

class JvmQobuzRequestDigest : QobuzRequestDigest {
    override fun md5Hex(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
