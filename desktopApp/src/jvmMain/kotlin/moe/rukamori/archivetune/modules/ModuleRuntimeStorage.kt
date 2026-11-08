package moe.rukamori.archivetune.modules

import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.desktop.integration.auth.DesktopSecureCredentialStore
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import org.json.JSONObject

/** The OS vault replaces Android's Keystore-backed file. There is no plaintext fallback. */
internal class ModuleRuntimeStorage(context: ModuleHostEnvironment, installation: String) {
    private val key = CredentialKey("modules", CredentialScope.PERSONAL, hash(installation))
    private val vault = DesktopSecureCredentialStore()
    fun read(scope: String): JSONObject = synchronized(lock) { runBlocking(Dispatchers.IO) {
        val root = vault.read(key)?.let(::JSONObject) ?: JSONObject()
        root.optJSONObject(scope) ?: JSONObject()
    } }
    fun write(scope: String, value: JSONObject): Unit = synchronized(lock) { runBlocking(Dispatchers.IO) {
        val root = vault.read(key)?.let(::JSONObject) ?: JSONObject()
        root.put(scope, value)
        val encoded = root.toString()
        require(encoded.toByteArray().size <= 1024 * 1024) { "Module state exceeds its bound" }
        vault.write(key, encoded)
    } }
    fun remove(): Unit = synchronized(lock) { runBlocking(Dispatchers.IO) { vault.remove(key) } }
    companion object {
        private val lock = Any()
        fun hash(value: String): String = hashBytes(value.toByteArray())
        fun hashBytes(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value)
            .joinToString("") { "%02x".format(it) }
    }
}
