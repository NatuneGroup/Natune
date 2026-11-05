package moe.rukamori.archivetune.desktop.storage

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import moe.rukamori.archivetune.desktop.desktopDiagnostic

/** One staged storage move. [source] is the resolved source directory; [destination] is the picked folder plus the source name. */
internal data class StagedStorageMigration(
    val id: String,
    val source: Path,
    val destination: Path,
    val createdAtMillis: Long,
) {
    init {
        require(id.isNotBlank() && id.length <= 128)
        require(source.isAbsolute && destination.isAbsolute)
        require(createdAtMillis >= 0)
    }
}

/**
 * The desktop storage-move contract: a move is staged by the settings page and applied by the next start.
 *
 * Nothing is copied while the session graph is open. [stage] validates the picked folder and writes [MARKER_FILE]
 * into the current directory, so every queue checkpoint, settings write and download that lands before the restart
 * stays in the source and is part of the snapshot the next start copies. [currentInstanceDirectory] applies the marker
 * before the session graph opens any file.
 *
 * Applying takes the source's single-instance lock for the length of the copy. That lock is the proof no writer has
 * the database open: the running instance holds it (a settings-page call therefore takes the same-JVM overlap path and
 * is a no-op), and a second launch that finds it held leaves the marker for the owner's next start. With no writer the
 * database file plus its `-wal` sidecar is a stable point-in-time set; the transient `-shm` index is deliberately not
 * copied because SQLite rebuilds it on open.
 *
 * The copy is assembled in a staging directory beside the destination, every file is fsynced, and the directory is
 * renamed into place only when complete. The staged copy carries the same marker as a receipt: a crash after the
 * rename but before the pointer write finishes the pointer on the next start instead of copying again. The pointer
 * file is replaced through a temporary file and an atomic move, so a crash leaves either the old or the new location
 * authoritative, never a torn path. Every failure leaves the source untouched and no pointer written; an invalid or
 * non-empty destination is discarded so it cannot block every later launch, while an I/O failure keeps the request for
 * a later attempt.
 */
internal object DesktopStorageRelocation {
    /** The pending request in the source directory, and the receipt in a completed destination. */
    const val MARKER_FILE = "storage-migration.json"

    private const val INSTANCE_LOCK = "instance.lock"
    private const val INSTANCE_ENDPOINT = "instance.endpoint"
    private const val INSTANCE_SOCKET = "instance.sock"
    private const val BROWSER_OWNER_LOCK = ".owner.lock"
    private const val DATABASE_SHARED_MEMORY = "portable-state.db-shm"
    private const val POINTER_DIRECTORY = ".archivetune"
    private const val POINTER_NAME = "desktop_location"
    private const val INSTANCE_DIRECTORY_PROPERTY = "archivetune.instance.directory"
    private const val MARKER_VERSION = 1
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val STAGING_PREFIX = ".archivetune-migration-"

    /** Instance control files that belong to one running process and are never part of the data snapshot. */
    private val CONTROL_FILES = setOf(INSTANCE_LOCK, INSTANCE_ENDPOINT, INSTANCE_SOCKET, BROWSER_OWNER_LOCK, DATABASE_SHARED_MEMORY, MARKER_FILE)

    private val json = Json { ignoreUnknownKeys = true }

    /** Fixed pointer file the next launch reads before opening the instance directory. */
    fun pointerFile(): Path = Path.of(System.getProperty("user.home"), POINTER_DIRECTORY, POINTER_NAME)

    /** The relocated directory when the pointer names an existing one, else [fallback]. */
    fun resolveStorageDirectory(fallback: Path, pointer: Path = pointerFile()): Path =
        runCatching {
            if (Files.isRegularFile(pointer)) {
                Path.of(Files.readString(pointer).trim()).takeIf { Files.isDirectory(it) }
            } else null
        }.getOrNull() ?: fallback

    /**
     * The instance directory this launch honours: an explicit override, else the relocated pointer, else the default.
     * A staged move is applied here, before the single-instance lock and the graph exist, and the returned directory is
     * the destination when the move completed.
     */
    fun currentInstanceDirectory(): Path {
        val configured = System.getProperty(INSTANCE_DIRECTORY_PROPERTY)?.let(Path::of)
        val fallback = configured ?: Path.of(System.getProperty("user.home"), POINTER_DIRECTORY, "desktop")
        return applyPendingForStartup(resolveStorageDirectory(fallback))
    }

    /** The pending move recorded under [source], or null. A marker that belongs to another folder is discarded. */
    fun pending(source: Path): StagedStorageMigration? {
        val directory = source.toAbsolutePath().normalize()
        val marker = directory.resolve(MARKER_FILE)
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return null
        val migration = readMarker(directory)
        if (migration == null || migration.source != sourceIdentity(directory)) {
            Files.deleteIfExists(marker)
            return null
        }
        return migration
    }

    /**
     * Validates [destination] and records the move for the next start. Nothing is copied; the running instance keeps
     * writing to [source] until it restarts, and those writes stay in the snapshot that start copies.
     */
    fun stage(source: Path, destination: Path): StagedStorageMigration {
        val from = sourceIdentity(source)
        checkDestination(from, destination)
        val migration = StagedStorageMigration(
            id = UUID.randomUUID().toString(),
            source = from,
            destination = destination.toAbsolutePath().normalize(),
            createdAtMillis = System.currentTimeMillis(),
        )
        writeFileAtomically(from.resolve(MARKER_FILE), encodeMarker(migration))
        return migration
    }

    /**
     * Applies a staged move for [current] when it is safe and returns the directory this launch must open. A pending
     * move whose source is still owned by a running instance is left in place; a move that cannot succeed for a
     * permanent reason is discarded so it never blocks every later launch. Any failure leaves the source authoritative:
     * no pointer is written and no data has moved out of it.
     */
    fun applyPendingForStartup(current: Path, pointer: Path = pointerFile()): Path {
        val directory = current.toAbsolutePath().normalize()
        return try {
            val migration = pending(directory) ?: return directory
            apply(directory, migration, pointer.toAbsolutePath().normalize())
        } catch (failure: Exception) {
            if (failure !is IOException) runCatching { Files.deleteIfExists(directory.resolve(MARKER_FILE)) }
            desktopDiagnostic("storage relocation not applied: ${failure.javaClass.simpleName}")
            directory
        }
    }

    private fun apply(source: Path, migration: StagedStorageMigration, pointer: Path): Path {
        val destination = migration.destination
        if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS) && pointerNames(pointer, destination)) {
            // A crash after the pointer write: the destination is the completed copy and only the source marker is left.
            runCatching { Files.deleteIfExists(source.resolve(MARKER_FILE)) }
            return destination
        }
        val receipt = readMarker(destination)
        if (receipt != null && receipt.id == migration.id && receipt.source == migration.source &&
            receipt.destination == migration.destination) {
            // A crash after the rename but before the pointer write: the copy is complete, finish the pointer.
            writePointerAtomically(destination, pointer)
            runCatching { Files.deleteIfExists(source.resolve(MARKER_FILE)) }
            runCatching { Files.deleteIfExists(destination.resolve(MARKER_FILE)) }
            return destination
        }
        return withExclusiveInstanceLock(source) {
            // Revalidate under the lock: with no writer the source file set is stable for the whole copy.
            checkDestination(source, destination)
            migrate(source, migration)
            writePointerAtomically(destination, pointer)
            // From here the pointer is authoritative; a cleanup failure must not fall back to the source.
            runCatching { Files.deleteIfExists(source.resolve(MARKER_FILE)) }
            runCatching { Files.deleteIfExists(destination.resolve(MARKER_FILE)) }
            destination
        } ?: run {
            desktopDiagnostic("storage relocation waits for the running instance")
            source
        }
    }

    /**
     * Copies the source into a staging directory beside the destination, fsyncs it and renames it into place. The
     * receipt inside the staged directory is what makes a crash between the rename and the pointer write recoverable.
     */
    private fun migrate(source: Path, migration: StagedStorageMigration) {
        val destination = migration.destination
        val parent = requireNotNull(destination.parent) { "Destination folder has no parent" }
        val staging = parent.resolve(STAGING_PREFIX + migration.id)
        try {
            deleteTree(staging)
            Files.createDirectory(staging)
            copyTree(source, staging)
            writeFileAtomically(staging.resolve(MARKER_FILE), encodeMarker(migration))
            copyPermissions(source, staging)
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                require(isEmptyDirectory(destination)) { "Destination folder is not empty" }
                Files.delete(destination)
            }
            try {
                Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, destination)
            }
            fsyncDirectory(parent)
        } catch (failure: Exception) {
            runCatching { deleteTree(staging) }
            throw failure
        }
    }

    /**
     * Validates that [destination] can receive a full copy. Called from the settings action and again under the source
     * lock at apply time, so a folder that changed in between is refused instead of merged into.
     */
    private fun checkDestination(source: Path, destination: Path) {
        val from = sourceIdentity(source)
        require(Files.isDirectory(from, LinkOption.NOFOLLOW_LINKS)) { "Current storage folder is missing" }
        val to = destination.toAbsolutePath().normalize()
        val name = to.fileName ?: throw IllegalArgumentException("Destination folder has no name")
        val parent = to.parent ?: throw IllegalArgumentException("Destination folder has no parent")
        require(!Files.isSymbolicLink(to)) { "Destination folder is a link" }
        require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) { "Destination parent folder is missing" }
        val parentReal = try {
            parent.toRealPath()
        } catch (failure: IOException) {
            throw IllegalArgumentException("Destination parent folder is unreadable", failure)
        }
        val resolved = parentReal.resolve(name.toString())
        require(resolved != from) { "Destination is the current storage folder" }
        require(!resolved.startsWith(from) && !from.startsWith(resolved)) { "Storage folders must not contain one another" }
        if (Files.exists(to, LinkOption.NOFOLLOW_LINKS)) {
            require(Files.isDirectory(to, LinkOption.NOFOLLOW_LINKS)) { "Destination is not a folder" }
            require(isEmptyDirectory(to)) { "Destination folder is not empty" }
        }
        probeWritable(parent)
        val required = dataBytes(from)
        require(Files.getFileStore(parentReal).usableSpace >= required) { "Not enough free space for the storage move" }
    }

    /** Copies every regular file and link under [source] into [destination], skipping this process's control files. */
    private fun copyTree(source: Path, destination: Path) {
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (directory != source) Files.createDirectories(destination.resolve(source.relativize(directory)))
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (file.fileName.toString() in CONTROL_FILES) return FileVisitResult.CONTINUE
                val target = destination.resolve(source.relativize(file))
                if (attributes.isSymbolicLink) {
                    // A browser profile carries links; recreate the link instead of following it out of the tree.
                    runCatching { Files.copy(file, target, LinkOption.NOFOLLOW_LINKS) }
                        .onFailure { desktopDiagnostic("storage relocation skipped link: ${it.javaClass.simpleName}") }
                    return FileVisitResult.CONTINUE
                }
                if (!attributes.isRegularFile) return FileVisitResult.CONTINUE
                copyFile(file, target)
                copyPermissions(file, target)
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, failure: IOException): FileVisitResult = throw failure

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                if (failure != null) throw failure
                if (directory != source) copyPermissions(directory, destination.resolve(source.relativize(directory)))
                return FileVisitResult.CONTINUE
            }
        })
    }

    /** Copies one file and forces it to the device, so the rename cannot publish a file with unwritten contents. */
    private fun copyFile(source: Path, destination: Path) {
        FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
            FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteBuffer.allocate(COPY_BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    buffer.flip()
                    while (buffer.hasRemaining()) output.write(buffer)
                    buffer.clear()
                }
                output.force(true)
            }
        }
    }

    /** POSIX modes are part of the stored data (private directories); a destination file system without them simply skips this. */
    private fun copyPermissions(source: Path, destination: Path) {
        try {
            if (!source.fileSystem.supportedFileAttributeViews().contains("posix")) return
            Files.setPosixFilePermissions(destination, Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS))
        } catch (_: Exception) {
            // The copied bytes still matter; a permission mode the destination cannot store does not fail the move.
        }
    }

    private fun dataBytes(source: Path): Long {
        var total = 0L
        Files.walkFileTree(source, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                if (attributes.isRegularFile && file.fileName.toString() !in CONTROL_FILES) total += attributes.size()
                return FileVisitResult.CONTINUE
            }
        })
        return total
    }

    private fun probeWritable(directory: Path) {
        // Creating and removing the probe proves the picked folder accepts files without leaving anything behind.
        Files.deleteIfExists(Files.createTempFile(directory, ".archivetune-migration-", ".probe"))
    }

    private fun isEmptyDirectory(directory: Path): Boolean =
        Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS) &&
            Files.newDirectoryStream(directory).use { !it.iterator().hasNext() }

    private fun withExclusiveInstanceLock(source: Path, block: () -> Path): Path? {
        val lockPath = source.resolve(INSTANCE_LOCK)
        if (Files.isSymbolicLink(lockPath)) return null
        val channel = try {
            FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        } catch (_: IOException) {
            return null
        }
        return channel.use {
            val lock = try {
                it.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            } catch (_: IOException) {
                null
            }
            if (lock == null) null else lock.use { block() }
        }
    }

    private fun pointerNames(pointer: Path, destination: Path): Boolean = try {
        Files.isRegularFile(pointer) &&
            Path.of(Files.readString(pointer).trim()).toAbsolutePath().normalize() == destination
    } catch (_: Exception) {
        false
    }

    private fun writePointerAtomically(destination: Path, pointer: Path) {
        writeFileAtomically(pointer, (destination.toString() + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Replaces [file] through a temporary file in the same directory and an atomic move. The directory is fsynced
     * after the move, so the rename itself is durable before the caller drops its marker.
     */
    private fun writeFileAtomically(file: Path, bytes: ByteArray) {
        val directory = requireNotNull(file.parent) { "File has no parent" }
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, ".archivetune-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
            fsyncDirectory(directory)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun fsyncDirectory(directory: Path) {
        runCatching {
            FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
        }
    }

    private fun readMarker(directory: Path): StagedStorageMigration? {
        val marker = directory.resolve(MARKER_FILE)
        if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) return null
        return decodeMarker(Files.readAllBytes(marker))
    }

    internal fun encodeMarker(migration: StagedStorageMigration): ByteArray = buildJsonObject {
        put("version", MARKER_VERSION)
        put("id", migration.id)
        put("source", migration.source.toString())
        put("destination", migration.destination.toString())
        put("createdAtMillis", migration.createdAtMillis)
    }.toString().toByteArray(StandardCharsets.UTF_8)

    internal fun decodeMarker(bytes: ByteArray): StagedStorageMigration? = try {
        val root = json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as JsonObject
        val version = (root["version"] as? JsonPrimitive)?.intOrNull
        val id = (root["id"] as? JsonPrimitive)?.contentOrNull
        val source = (root["source"] as? JsonPrimitive)?.contentOrNull
        val destination = (root["destination"] as? JsonPrimitive)?.contentOrNull
        val createdAt = (root["createdAtMillis"] as? JsonPrimitive)?.longOrNull
        if (version != MARKER_VERSION || id.isNullOrBlank() || source.isNullOrBlank() ||
            destination.isNullOrBlank() || createdAt == null) null
        else StagedStorageMigration(id, Path.of(source), Path.of(destination), createdAt)
    } catch (_: Exception) {
        null
    }

    private fun sourceIdentity(source: Path): Path = try {
        source.toRealPath()
    } catch (_: IOException) {
        source.toAbsolutePath().normalize()
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                runCatching { file.toFile().setWritable(true) }
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(directory: Path, failure: IOException?): FileVisitResult {
                runCatching { directory.toFile().setWritable(true) }
                Files.deleteIfExists(directory)
                return FileVisitResult.CONTINUE
            }
        })
    }
}
