/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.platform.*

/** Host singleton. Never falls back to plaintext or reads legacy catalog credentials for streaming. */
class AndroidSecureCredentialStore(context: Context) : SecureCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences("kmp_secure_credentials", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun read(key: CredentialKey): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = identity(key)
            val stored = preferences.getString(identity, null) ?: return@withLock null
            val parts = stored.split(':')
            require(parts.size == 3 && parts[0] == "1") { "Unsupported encrypted credential format" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keyFor(key.scope), GCMParameterSpec(128, decode(parts[1])))
            cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
            cipher.doFinal(decode(parts[2])).toString(Charsets.UTF_8)
        }
    }

    override suspend fun write(key: CredentialKey, value: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val identity = identity(key)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keyFor(key.scope))
            cipher.updateAAD(identity.toByteArray(Charsets.UTF_8))
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            check(preferences.edit().putString(identity, "1:${encode(cipher.iv)}:${encode(encrypted)}").commit()) {
                "Could not commit encrypted credential"
            }
        }
    }

    override suspend fun remove(key: CredentialKey) = withContext(Dispatchers.IO) {
        mutex.withLock {
            check(preferences.edit().remove(identity(key)).commit()) { "Could not remove encrypted credential" }
        }
    }

    private fun identity(key: CredentialKey): String = ContentDigest.sha256(
        listOf(key.provider, key.scope.name, key.name).joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8))

    private fun keyFor(scope: CredentialScope): SecretKey {
        val alias = "archivetune.kmp.credentials.${scope.name.lowercase()}"
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun decode(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
