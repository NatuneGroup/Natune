/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.auth

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.NativeDesktopBridge
import moe.rukamori.archivetune.shared.data.platform.desktop.DesktopPlatformFiles
import moe.rukamori.archivetune.shared.domain.platform.CredentialKey
import moe.rukamori.archivetune.shared.domain.platform.FileHandle
import moe.rukamori.archivetune.shared.domain.platform.SecureCredentialStore

/** A capability failure, never a missing password or permission to fall back to preferences. */
class DesktopCredentialStoreUnavailable : IllegalStateException("Desktop credential service unavailable")

internal const val MAX_CREDENTIAL_BYTES = 1024 * 1024
internal interface CredentialBackend {
    suspend fun read(account: String): ByteArray?
    suspend fun write(account: String, value: ByteArray)
    suspend fun remove(account: String)
}

/** Uses the committed M7 key/scope contract, not a second credential/account model. */
class DesktopSecureCredentialStore internal constructor(private val backend: CredentialBackend) : SecureCredentialStore {
    constructor() : this(platformBackend())
    override suspend fun read(key: CredentialKey): String? = guarded {
        val bytes = backend.read(credentialAccount(key)) ?: return@guarded null
        try {
            check(bytes.size <= MAX_CREDENTIAL_BYTES)
            bytes.decodeToString(throwOnInvalidSequence = true)
        } finally { bytes.fill(0) }
    }
    override suspend fun write(key: CredentialKey, value: String) = guarded {
        require(value.length <= MAX_CREDENTIAL_BYTES)
        val bytes = value.encodeToByteArray(throwOnInvalidSequence = true)
        try {
            require(bytes.size <= MAX_CREDENTIAL_BYTES)
            backend.write(credentialAccount(key), bytes)
        } finally { bytes.fill(0) }
    }
    override suspend fun remove(key: CredentialKey) = guarded { backend.remove(credentialAccount(key)) }

    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (invalid: IllegalArgumentException) { throw invalid }
    catch (_: LinkageError) { throw DesktopCredentialStoreUnavailable() }
    catch (_: Exception) { throw DesktopCredentialStoreUnavailable() }
}

internal fun credentialAccount(key: CredentialKey): String {
    val encoded = ByteArrayOutputStream()
    DataOutputStream(encoded).use { data ->
        for (part in listOf("archivetune.credentials.v1", key.provider, key.scope.name, key.name)) {
            require(part.length in 1..4096)
            val bytes = part.encodeToByteArray(throwOnInvalidSequence = true)
            require(bytes.size <= 4096)
            data.writeInt(bytes.size)
            data.write(bytes)
        }
    }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray()).joinToString("") { "%02x".format(it) }
}

private fun platformBackend(): CredentialBackend = when {
    System.getProperty("os.name").startsWith("Linux") -> LinuxSecretServiceBackend()
    System.getProperty("os.name").startsWith("Mac") -> MacKeychainBackend()
    System.getProperty("os.name").startsWith("Windows") -> WindowsDpapiBackend(
        Path.of(System.getenv("LOCALAPPDATA") ?: throw DesktopCredentialStoreUnavailable(), "ArchiveTune", "credentials"),
    )
    else -> throw DesktopCredentialStoreUnavailable()
}

internal object DesktopCredentialNative {
    fun load() = NativeDesktopBridge.ensureLoaded()
    external fun protect(value: ByteArray, entropy: ByteArray): ByteArray
    external fun unprotect(value: ByteArray, entropy: ByteArray): ByteArray
    external fun keychainRead(account: String): ByteArray?
    external fun keychainWrite(account: String, value: ByteArray)
    external fun keychainRemove(account: String)
}

private class MacKeychainBackend : CredentialBackend {
    override suspend fun read(account: String) = withContext(Dispatchers.IO) {
        DesktopCredentialNative.load(); DesktopCredentialNative.keychainRead(account)
    }
    override suspend fun write(account: String, value: ByteArray) = withContext(Dispatchers.IO) {
        DesktopCredentialNative.load(); DesktopCredentialNative.keychainWrite(account, value)
    }
    override suspend fun remove(account: String) = withContext(Dispatchers.IO) {
        DesktopCredentialNative.load(); DesktopCredentialNative.keychainRemove(account)
    }
}

/** Only user-DPAPI ciphertext reaches disk; scope-bound entropy prevents relabelling another slot. */
internal class WindowsDpapiBackend(
    private val directory: Path,
    private val protect: (ByteArray, ByteArray) -> ByteArray = { value, entropy ->
        DesktopCredentialNative.load(); DesktopCredentialNative.protect(value, entropy)
    },
    private val unprotect: (ByteArray, ByteArray) -> ByteArray = { value, entropy ->
        DesktopCredentialNative.load(); DesktopCredentialNative.unprotect(value, entropy)
    },
    private val ensureAvailable: () -> Unit = { DesktopCredentialNative.load() },
) : CredentialBackend {
    private val mutex = Mutex()
    private val files = DesktopPlatformFiles()
    private fun target(account: String): Path {
        require(account.matches(Regex("[0-9a-f]{64}")) && directory.isAbsolute)
        check(!Files.isSymbolicLink(directory))
        Files.createDirectories(directory)
        return directory.resolve("$account.dpapi").also { check(!Files.isSymbolicLink(it)) }
    }
    override suspend fun read(account: String): ByteArray? = withContext(Dispatchers.IO) { mutex.withLock {
        ensureAvailable()
        val path = target(account)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withLock null
        val cipher = files.read(FileHandle(path.toString()), MAX_CREDENTIAL_BYTES + 8192L)
        try { unprotect(cipher, account.toByteArray(Charsets.US_ASCII)) } finally { cipher.fill(0) }
    } }
    override suspend fun write(account: String, value: ByteArray) = withContext(Dispatchers.IO) { mutex.withLock {
        ensureAvailable()
        // Protect before creating/replacing anything: DPAPI failure must preserve the existing slot.
        val cipher = protect(value, account.toByteArray(Charsets.US_ASCII))
        try {
            check(cipher.isNotEmpty() && cipher.size <= MAX_CREDENTIAL_BYTES + 8192)
            files.writeAtomic(FileHandle(target(account).toString()), cipher)
        } finally { cipher.fill(0) }
    } }
    override suspend fun remove(account: String) = withContext(Dispatchers.IO) { mutex.withLock {
        ensureAvailable()
        files.delete(FileHandle(target(account).toString()))
    } }
}
