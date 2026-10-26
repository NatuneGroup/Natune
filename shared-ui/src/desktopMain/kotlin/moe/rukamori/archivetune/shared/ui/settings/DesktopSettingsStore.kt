/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.settings

import java.io.*
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.CRC32
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.settings.*

/**
 * One owner per path, including across processes. Hosts open once before creating settings consumers,
 * then close after quiescing them. A corrupt/unreadable existing file is an error, never defaults.
 *
 * This is private local preferences storage, NOT a credential vault: known credential keys are
 * rejected on write. Open VaultSettingsStore over this owner before exposing credential consumers;
 * existing plaintext credentials are read only for that explicit migration, never as a fallback.
 * File contents are forced before atomic rename; directory-entry durability
 * across sudden power loss is not guaranteed on every filesystem.
 * The internal binary format is lossless for all seven preference kinds and
 * dynamic keys; backup/restore still exchanges the canonical PreferenceSnapshot (settings.xml stays
 * owned by the backup codec, not a second desktop archive schema).
 */
class DesktopSettingsStore private constructor(
    private val path: Path,
    private val owner: FileChannel,
    private val ownerLock: FileLock,
    initial: PreferenceSnapshot,
) : SettingsStore {
    private val lock = Mutex()
    private var closed = false
    private val state = MutableStateFlow<PreferenceSnapshot?>(initial)
    override val snapshots = state.asStateFlow()

    suspend fun snapshot(): PreferenceSnapshot = lock.withLock {
        check(!closed) { "Settings store is closed" }
        requireNotNull(state.value)
    }

    override suspend fun edit(change: PreferenceEditor.() -> Unit): SettingsChange = lock.withLock {
        check(!closed) { "Settings store is closed" }
        val before = requireNotNull(state.value)
        commit(before, before.toEditor().apply(change).snapshot())
    }

    /** Restore replaces the whole typed snapshot, including removals, in one durable commit. */
    suspend fun replaceAll(snapshot: PreferenceSnapshot): SettingsChange = lock.withLock {
        check(!closed) { "Settings store is closed" }
        commit(requireNotNull(state.value), snapshot)
    }

    private suspend fun commit(before: PreferenceSnapshot, after: PreferenceSnapshot): SettingsChange {
        requireCredentialFreePersistence(after)
        val change = SettingsChange(before, after)
        if (change.changedKeys.isEmpty()) return change
        // Cancellation must not split the atomic rename from publication.
        withContext(Dispatchers.IO + NonCancellable) {
            val bytes = DesktopPreferenceFile.encode(after)
            val temporary = privateTempFile(path.parent)
            try {
                FileChannel.open(temporary, WRITE).use { channel ->
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                // No non-atomic fallback: unsupported filesystems fail without publishing.
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                state.value = after
            } finally { Files.deleteIfExists(temporary) }
        }
        return change
    }

    suspend fun close() = withContext(Dispatchers.IO + NonCancellable) {
        lock.withLock {
            if (!closed) {
                closed = true
                try { ownerLock.release() } finally { owner.close() }
            }
        }
    }

    companion object {
        suspend fun open(file: Path): DesktopSettingsStore {
            var opened: DesktopSettingsStore? = null
            try {
                return withContext(Dispatchers.IO) { openLocked(file).also { opened = it } }
            } catch (failure: Throwable) {
                // withContext can discard its result when the caller cancels during the disk read.
                opened?.close()
                throw failure
            }
        }

        private fun openLocked(file: Path): DesktopSettingsStore {
            val path = file.toAbsolutePath().normalize()
            Files.createDirectories(requireNotNull(path.parent))
            require(!Files.isSymbolicLink(path)) { "Settings file must not be a symbolic link" }
            val lockPath = path.resolveSibling("${path.fileName}.lock")
            val channel = FileChannel.open(lockPath, setOf(CREATE, WRITE, LinkOption.NOFOLLOW_LINKS), *privateAttributes(path.parent))
            return try {
                val held = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                if (held == null) throw IOException("Settings file already has an owner")
                try {
                    val initial = if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                        Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS).use { input ->
                            val bytes = input.readNBytes(DesktopPreferenceFile.MAX_BYTES + 1)
                            if (bytes.size > DesktopPreferenceFile.MAX_BYTES) throw IOException("Settings file exceeds size limit")
                            DesktopPreferenceFile.decode(bytes)
                        }
                    } else PreferenceSnapshot.EMPTY
                    DesktopSettingsStore(path, channel, held, initial)
                } catch (failure: Throwable) {
                    held.release()
                    throw failure
                }
            } catch (failure: Throwable) {
                channel.close()
                throw failure
            }
        }

        private fun privateAttributes(parent: Path) =
            if (Files.getFileStore(parent).supportsFileAttributeView("posix"))
                arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            else emptyArray()

        private fun privateTempFile(parent: Path): Path =
            Files.createTempFile(parent, ".settings-", ".tmp", *privateAttributes(parent))
    }
}

/** Versioned private file; names and kind identities are explicit, never enum ordinals. */
private object DesktopPreferenceFile {
    const val MAX_BYTES = 16 * 1024 * 1024
    private const val MAX_STRING = 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAGIC = 0x41545331 // ATS1

    fun encode(snapshot: PreferenceSnapshot): ByteArray {
        require(snapshot.keys.size <= MAX_ENTRIES)
        val payload = ByteArrayOutputStream()
        DataOutputStream(payload).use { output ->
            output.writeInt(snapshot.keys.size)
            snapshot.keys.sortedWith(compareBy({ it.name }, { it.kind.name })).forEach { key ->
                output.string(key.kind.name)
                output.string(key.name)
                when (key) {
                    is BooleanPreferenceKey -> output.writeBoolean(snapshot[key]!!)
                    is StringPreferenceKey -> output.string(snapshot[key]!!)
                    is IntPreferenceKey -> output.writeInt(snapshot[key]!!)
                    is LongPreferenceKey -> output.writeLong(snapshot[key]!!)
                    is FloatPreferenceKey -> output.writeInt(snapshot[key]!!.toRawBits())
                    is DoublePreferenceKey -> output.writeLong(snapshot[key]!!.toRawBits())
                    is StringSetPreferenceKey -> {
                        val values = snapshot[key]!!
                        require(values.size <= MAX_ENTRIES)
                        output.writeInt(values.size)
                        values.sorted().forEach { output.string(it) }
                    }
                }
                require(payload.size() <= MAX_BYTES - 16) { "Settings file exceeds size limit" }
            }
        }
        val data = payload.toByteArray()
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use {
                it.writeInt(MAGIC); it.writeInt(data.size)
                it.writeLong(CRC32().apply { update(data) }.value)
                it.write(data)
            }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): PreferenceSnapshot = try {
        DataInputStream(ByteArrayInputStream(bytes)).use { file ->
            require(bytes.size <= MAX_BYTES && file.readInt() == MAGIC)
            val size = file.readInt()
            val checksum = file.readLong()
            require(size == bytes.size - 16)
            val data = file.readNBytes(size)
            require(CRC32().apply { update(data) }.value == checksum)
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                val entries = List(input.count()) {
                    val kind = input.string()
                    val name = input.string()
                    val entry: Pair<PreferenceKey<*>, Any> = when (kind) {
                        "BOOLEAN" -> BooleanPreferenceKey(name) to input.readUnsignedByte().also { require(it <= 1) }.let { it == 1 }
                        "STRING" -> StringPreferenceKey(name) to input.string()
                        "INT" -> IntPreferenceKey(name) to input.readInt()
                        "LONG" -> LongPreferenceKey(name) to input.readLong()
                        "FLOAT" -> FloatPreferenceKey(name) to Float.fromBits(input.readInt())
                        "DOUBLE" -> DoublePreferenceKey(name) to Double.fromBits(input.readLong())
                        "STRING_SET" -> StringSetPreferenceKey(name) to List(input.count()) { input.string() }.toSet()
                        else -> throw IOException("Unrecognized settings value kind")
                    }
                    entry
                }
                require(input.available() == 0 && entries.map { it.first }.distinct().size == entries.size)
                PreferenceSnapshot.of(*entries.toTypedArray())
            }
        }
    } catch (failure: Exception) { throw IOException("Invalid settings file", failure) }

    private fun DataOutputStream.string(value: String) {
        require(value.length <= MAX_STRING) { "Settings string exceeds size limit" }
        val bytes = Charsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(value))
        require(bytes.remaining() <= MAX_STRING && size().toLong() + 4 + bytes.remaining() <= MAX_BYTES - 16) {
            "Settings file exceeds size limit"
        }
        writeInt(bytes.remaining())
        val data = ByteArray(bytes.remaining())
        bytes.get(data)
        write(data)
    }

    private fun DataInputStream.string(): String {
        val size = readInt()
        require(size in 0..MAX_STRING && size <= available())
        return Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readNBytes(size))).toString()
    }

    private fun DataInputStream.count(): Int = readInt().also { require(it in 0..MAX_ENTRIES) }
}
