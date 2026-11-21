package app.natune.backup

import android.util.Base64
import androidx.datastore.preferences.core.Preferences
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import app.natune.kmp.settings.sharedSnapshot
import app.natune.shared.data.backup.BackupSettingsReader
import app.natune.shared.data.backup.PortableBackupFormat
import app.natune.shared.data.persistence.LegacyDataStorePreferences
import app.natune.shared.domain.settings.BooleanPreferenceKey
import app.natune.shared.domain.settings.DoublePreferenceKey
import app.natune.shared.domain.settings.FloatPreferenceKey
import app.natune.shared.domain.settings.IntPreferenceKey
import app.natune.shared.domain.settings.LongPreferenceKey
import app.natune.shared.domain.settings.PreferenceKey
import app.natune.shared.domain.settings.StringPreferenceKey
import app.natune.shared.domain.settings.StringSetPreferenceKey
import app.natune.shared.domain.settings.PreferenceSnapshot

/** The password did not open the backup's sign-ins, or the section was altered. Its message is shown as is. */
class BackupPasswordException(message: String) : IllegalArgumentException(message)

/**
 * Sign-ins in a backup, encrypted with a password the user chooses, so a restore brings them back without the
 * file exposing them. `accounts.enc` holds a JSON envelope: PBKDF2 parameters and salt, the GCM nonce, and the
 * ciphertext of a `settings.json`-layout payload with only account keys. The envelope fields are bound into the
 * GCM tag, so a changed iteration count or salt fails the same way a wrong password does.
 */
internal object BackupAccountVault {
    const val ENTRY = "accounts.enc"
    private const val FORMAT = "natune.accounts"
    private const val VERSION = 1
    private const val ITERATIONS = 310_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    /** The account keys a vault carries: every sign-in key except values sealed to this device. */
    fun accountsOf(preferences: Preferences): PreferenceSnapshot =
        LegacyDataStorePreferences.filtered(preferences.sharedSnapshot()) {
            BackupPreferenceSelection.includes(it, settings = false, account = true)
        }

    fun seal(accounts: PreferenceSnapshot, password: String): ByteArray {
        require(password.isNotEmpty()) { "A password is required to back up sign-ins" }
        val kdf = preferredKdf()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(kdf, ITERATIONS, salt, nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(kdf, password, salt, ITERATIONS), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad(header))
        val ciphertext = cipher.doFinal(PortableBackupFormat.encodeRestoreSettings(accounts))
        return JsonObject(header + ("ciphertext" to JsonPrimitive(encode(ciphertext)))).toString().encodeToByteArray()
    }

    fun open(envelope: ByteArray, password: String, wrongPassword: String): PreferenceSnapshot {
        val root = runCatching { json.parseToJsonElement(envelope.decodeToString()) as JsonObject }.getOrNull()
            ?: throw BackupPasswordException(wrongPassword)
        val kdf = root.text("kdf")
        val iterations = (root["iterations"] as? JsonPrimitive)?.intOrNull
        val salt = root.text("salt")?.let(::decode)
        val nonce = root.text("nonce")?.let(::decode)
        val ciphertext = root.text("ciphertext")?.let(::decode)
        if (root.text("format") != FORMAT || kdf == null || iterations == null || iterations !in 1..MAX_ITERATIONS ||
            salt == null || nonce == null || ciphertext == null
        ) {
            throw BackupPasswordException(wrongPassword)
        }
        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(kdf, password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(aad(header(kdf, iterations, salt, nonce)))
            cipher.doFinal(ciphertext)
        } catch (_: GeneralSecurityException) {
            throw BackupPasswordException(wrongPassword)
        }
        // Only sign-in keys leave the vault, whatever a modified payload holds.
        return LegacyDataStorePreferences.filtered(BackupSettingsReader.read(plain, foreign = false).settings) {
            BackupPreferenceSelection.includes(it, settings = false, account = true)
        }
    }

    /** Adds [accounts] to the staged settings XML (sign-ins win over a same-named setting), creating it if absent. */
    fun mergeIntoStagedXml(xml: File, accounts: PreferenceSnapshot, includeKey: (String) -> Boolean) {
        val staged = if (xml.isFile) FileInputStream(xml).use { readLegacySettings(it) } else LegacySettingsValues()
        val editor = PreferenceSnapshot.EMPTY.toEditor()
        staged.booleans.forEach { (name, value) -> editor[BooleanPreferenceKey(name)] = value }
        staged.ints.forEach { (name, value) -> editor[IntPreferenceKey(name)] = value }
        staged.longs.forEach { (name, value) -> editor[LongPreferenceKey(name)] = value }
        staged.floats.forEach { (name, value) -> editor[FloatPreferenceKey(name)] = value }
        staged.doubles.forEach { (name, value) -> editor[DoublePreferenceKey(name)] = value }
        staged.strings.forEach { (name, value) -> editor[StringPreferenceKey(name)] = value }
        staged.stringSets.forEach { (name, value) -> editor[StringSetPreferenceKey(name)] = value }
        accounts.keys.forEach { key ->
            @Suppress("UNCHECKED_CAST")
            editor[key as PreferenceKey<Any>] = accounts[key]!!
        }
        FileOutputStream(xml).use { writeLegacySettingsXml(it, editor.snapshot(), includeKey) }
    }

    private fun header(kdf: String, iterations: Int, salt: ByteArray, nonce: ByteArray): Map<String, JsonPrimitive> =
        linkedMapOf(
            "format" to JsonPrimitive(FORMAT),
            "version" to JsonPrimitive(VERSION),
            "kdf" to JsonPrimitive(kdf),
            "iterations" to JsonPrimitive(iterations),
            "salt" to JsonPrimitive(encode(salt)),
            "nonce" to JsonPrimitive(encode(nonce)),
        )

    private fun aad(header: Map<String, JsonPrimitive>): ByteArray = JsonObject(header).toString().encodeToByteArray()

    /** PBKDF2 with SHA-256 from Android 8; Android 7 only has the SHA-1 variant, which the envelope records. */
    private fun preferredKdf(): String =
        listOf("PBKDF2WithHmacSHA256", "PBKDF2WithHmacSHA1").first { name ->
            runCatching { SecretKeyFactory.getInstance(name) }.isSuccess
        }

    private fun derive(kdf: String, password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray? = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull()

    private fun JsonObject.text(field: String): String? = (this[field] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
