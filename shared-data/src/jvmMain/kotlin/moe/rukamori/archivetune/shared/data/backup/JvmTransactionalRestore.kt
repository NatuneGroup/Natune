package moe.rukamori.archivetune.shared.data.backup

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.persistence.BackupCategory
import moe.rukamori.archivetune.shared.domain.persistence.*
import moe.rukamori.archivetune.shared.domain.settings.*

class RestoreRollbackFailure(
    val recoveryDirectory: Path,
    val originalFiles: Map<Path, Path?>,
    val originalPreferences: PreferenceSnapshot,
    cause: Throwable,
) :
    Exception("Restore rollback failed; keep host quiesced and recover retained files", cause)

/**
 * Before commit, failures and observed cancellation roll back under a REAL host-wide quiescence
 * boundary. Individual replacements are atomic, not a cross-filesystem process-crash transaction.
 * As with database commits, cancellation delivered AFTER commit can leave the caller's outcome
 * uncertain; it does not undo a committed restore after the exclusive host boundary has resumed.
 * Host database/settings implementations are required; there are no production no-op defaults.
 */
class JvmTransactionalRestore(
    databaseDirectory: Path,
    fontsDirectory: Path,
    private val preferences: RestorePreferences,
    private val lifecycle: RestoreLifecycle,
) {
    private val databaseRoot = directory(databaseDirectory)
    private val fontsRoot = directory(fontsDirectory)
    private val mutex = Mutex()
    private var recoveryFailure: RestoreRollbackFailure? = null

    suspend fun restore(archive: ByteArray, selected: Set<BackupCategory>) {
        val ownedArchive = archive.copyOf()
        val categories = selected.toSet()
        withContext(Dispatchers.IO) {
            val prepared = JvmBackupArchiveCodec().prepare(ownedArchive, categories)
            mutex.withLock {
                recoveryFailure?.let { throw it }
                lifecycle.withExclusiveAccess {
                    apply(prepared, BackupCategory.LIBRARY in categories)
                }
            }
        }
    }
    private suspend fun apply(prepared: PreparedBackup, library: Boolean) {
        var retainRecovery = false
        var databaseClosed = false
        var changed = false
        val oldFiles = linkedMapOf<Path, Path?>()
        val incoming = linkedMapOf<Path, Path?>()
        val before = preferences.snapshot()
        val merged = before.keys.associateWith { before[it]!! }.toMutableMap()
        prepared.settings.keys.forEach { merged[it] = prepared.settings[it]!! }
        val replacement = PreferenceSnapshot.of(*merged.entries.map { it.key to it.value }.toTypedArray())
            .toEditor().apply { normalizeRestoredSettings(prepared.settings.keys.map { it.name }.toSet()) }.snapshot()
        val work = Files.createTempDirectory(databaseRoot, ".restore-")
        try {
            if (library) DB_FILES.forEach { name ->
                val target = child(databaseRoot, name)
                incoming[target] = prepared.libraryFiles[name]?.let { stage(target, it) }
            }
            prepared.fonts.forEach { (name, bytes) ->
                val target = child(fontsRoot, name)
                incoming[target] = stage(target, bytes)
            }
            currentCoroutineContext().ensureActive()
            if (library) {
                databaseClosed = true // close may fail after partially closing the host.
                lifecycle.closeDatabase()
            }
            // Capture only after DB shutdown, while ALL writers remain excluded by the host.
            incoming.keys.forEachIndexed { index, target ->
                oldFiles[target] = if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    require(Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) { "Restore target is not a regular file" }
                    work.resolve("old-$index").also { copySynced(target, it) }
                } else null
            }
            changed = true // A failing atomic preference port may already have committed.
            incoming.forEach { (target, source) ->
                currentCoroutineContext().ensureActive()
                require(!Files.isSymbolicLink(target))
                if (source == null) Files.deleteIfExists(target)
                else Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
            if (prepared.settings.keys.isNotEmpty()) preferences.replaceAll(replacement)
            currentCoroutineContext().ensureActive()
            if (library) lifecycle.reopenAndValidateDatabase()
            currentCoroutineContext().ensureActive() // Last cancellation check before commit.
        } catch (failure: Throwable) {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (changed) {
                        if (library) lifecycle.closeDatabase() // Candidate may have reopened before failing validation.
                        oldFiles.forEach { (target, source) -> replace(target, source) }
                        preferences.replaceAll(before)
                    }
                    if (databaseClosed) lifecycle.reopenAndValidateDatabase()
                }
            } catch (rollback: Throwable) {
                retainRecovery = true
                val fatal = RestoreRollbackFailure(work, oldFiles.toMap(), before, failure)
                fatal.addSuppressed(rollback)
                recoveryFailure = fatal
                throw fatal
            }
            throw failure
        } finally {
            // Failure to clean harmless staging must not change a committed/rolled-back outcome.
            // A rollback failure deliberately leaves originals for explicit recovery.
            if (!retainRecovery) {
                try { work.toFile().deleteRecursively() } catch (_: SecurityException) { /* Private harmless staging only. */ }
            }
            incoming.values.filterNotNull().forEach(::cleanStaging)
        }
    }

    private fun stage(target: Path, bytes: ByteArray): Path {
        // Complete EVERY candidate on its own filesystem before closing/mutating host state.
        val path = Files.createTempFile(target.parent, ".restore-file-", ".part")
        return try { writeSynced(path, bytes); path } catch (failure: Throwable) {
            try { Files.deleteIfExists(path) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }
    private fun replace(target: Path, source: Path?) {
        require(!Files.isSymbolicLink(target))
        if (source == null) { Files.deleteIfExists(target); return }
        // Stage on the TARGET filesystem; fonts and the database can live on different volumes.
        val staging = Files.createTempFile(target.parent, ".restore-file-", ".part")
        try {
            copySynced(source, staging)
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { cleanStaging(staging) }
    }
    private fun cleanStaging(path: Path) {
        try { Files.deleteIfExists(path) }
        catch (_: java.io.IOException) { /* Private unpromoted staging must not mask the outcome. */ }
        catch (_: SecurityException) { /* Same cleanup-only rule. */ }
    }
    private fun writeSynced(path: Path, bytes: ByteArray) {
        FileChannel.open(path, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE).use { channel ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
    }
    private fun copySynced(source: Path, target: Path) {
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(target, StandardOpenOption.WRITE).use { it.force(true) }
    }
    private companion object {
        val DB_FILES = listOf("song.db", "song.db-wal", "song.db-shm", "song.db-journal")
        fun directory(path: Path): Path {
            require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
            return path.toRealPath()
        }
        fun child(root: Path, name: String): Path {
            require(name.isNotBlank() && name.none { it == '/' || it == '\\' || it == '\u0000' } && name != "." && name != "..")
            return root.resolve(name).also { require(!Files.isSymbolicLink(it)) }
        }
    }
}
