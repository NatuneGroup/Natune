package moe.rukamori.archivetune.shared.data.customartwork

import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkDirectory
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkFiles
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkKeys
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkLimits
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkOverrides
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkReader
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkResult
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkSource
import moe.rukamori.archivetune.shared.domain.customartwork.CustomArtworkWriter
import moe.rukamori.archivetune.shared.domain.repository.CustomArtworkRepository

/**
 * Custom covers and canvases stored as versioned files. This is the commonMain port of the Android
 * `CustomArtworkStore`, and it keeps that store's on-disk layout, so files a user already picked resolve without a
 * migration:
 * - A version is named `<key>_<epoch millis>[.extension]`. Each new version gets a new name, so a cached image or
 *   video can never serve the file it replaced.
 * - A copy streams into `<key>.tmp`, and the file is renamed to its version only after it passes the size limit and
 *   the host's decodability check. A failed copy removes its temporary file.
 * - A scan removes leftovers, ignores empty files, and keeps the newest version of each key.
 *
 * Writes run under [NonCancellable], as the Android store did. A cancelled caller must not leave the state map and
 * the folder disagreeing, so a version is either committed to the map or removed from the folder.
 */
class FileCustomArtworkRepository(
    private val files: CustomArtworkFiles,
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : CustomArtworkRepository {
    private val mutableOverrides = MutableStateFlow(CustomArtworkOverrides())
    override val overrides: StateFlow<CustomArtworkOverrides> = mutableOverrides.asStateFlow()

    // Guards the maps together with the files they name, so a commit and a removal never interleave.
    private val stateLock = Mutex()

    // One copy at a time. Two copies of the same key would share one temporary name and overwrite each other.
    private val writeLock = Mutex()

    private var loaded = false

    override suspend fun ensureLoaded() {
        stateLock.withLock {
            if (loaded) return
            mutableOverrides.value =
                CustomArtworkOverrides(
                    covers = scan(CustomArtworkDirectory.COVERS),
                    canvases = scan(CustomArtworkDirectory.CANVASES),
                )
            loaded = true
        }
    }

    override suspend fun coverUri(artworkUrl: String?): String? {
        ensureLoaded()
        val key = CustomArtworkKeys.coverKey(artworkUrl) ?: return null
        val name = mutableOverrides.value.covers[key] ?: return null
        return files.uriOf(CustomArtworkDirectory.COVERS, name)
    }

    override suspend fun canvasUri(songId: String?): String? {
        ensureLoaded()
        val key = CustomArtworkKeys.canvasKey(songId) ?: return null
        val name = mutableOverrides.value.canvases[key] ?: return null
        return files.uriOf(CustomArtworkDirectory.CANVASES, name)
    }

    override suspend fun setCover(artworkUrl: String, source: CustomArtworkSource): CustomArtworkResult =
        withContext(NonCancellable) {
            ensureLoaded()
            // An artwork URL with no usable identity, such as a custom cover path, has nothing to replace.
            val key = CustomArtworkKeys.coverKey(artworkUrl) ?: return@withContext CustomArtworkResult.UNSUPPORTED
            writeLock.withLock { writeCover(key, source) }
        }

    override suspend fun setCanvas(songId: String, source: CustomArtworkSource): CustomArtworkResult =
        withContext(NonCancellable) {
            ensureLoaded()
            val key = CustomArtworkKeys.canvasKey(songId) ?: return@withContext CustomArtworkResult.UNSUPPORTED
            // The extension is decided before any byte is copied, so a refused file never touches the folder.
            val extension =
                CustomArtworkKeys.canvasExtension(source.mimeType, source.fileExtension)
                    ?: return@withContext CustomArtworkResult.UNSUPPORTED
            writeLock.withLock { writeCanvas(key, extension, source) }
        }

    override suspend fun removeCover(artworkUrl: String?): Boolean =
        withContext(NonCancellable) {
            ensureLoaded()
            val key = CustomArtworkKeys.coverKey(artworkUrl) ?: return@withContext false
            remove(CustomArtworkDirectory.COVERS, key)
        }

    override suspend fun removeCanvas(songId: String?): Boolean =
        withContext(NonCancellable) {
            ensureLoaded()
            val key = CustomArtworkKeys.canvasKey(songId) ?: return@withContext false
            remove(CustomArtworkDirectory.CANVASES, key)
        }

    /**
     * Copies a cover and publishes it only after the host confirms it decodes. An undecodable file is removed before
     * anything is published, so the map never names an image that would show broken.
     */
    private suspend fun writeCover(key: String, source: CustomArtworkSource): CustomArtworkResult {
        val directory = CustomArtworkDirectory.COVERS
        val name =
            when (val copied = copyIn(directory, key, null, CustomArtworkLimits.MAX_COVER_BYTES, source)) {
                is CopyOutcome.Stored -> copied.name
                is CopyOutcome.Rejected -> return copied.result
            }
        if (!files.isDecodableImage(directory, name)) {
            files.delete(directory, name)
            return CustomArtworkResult.UNSUPPORTED
        }
        commit(directory, key, name)
        return CustomArtworkResult.SAVED
    }

    /**
     * Copies a canvas and publishes it. Only image canvases are probed, because a video has no still image for the
     * host to decode, and the extension check already restricted the file to the accepted formats.
     */
    private suspend fun writeCanvas(key: String, extension: String, source: CustomArtworkSource): CustomArtworkResult {
        val directory = CustomArtworkDirectory.CANVASES
        val name =
            when (val copied = copyIn(directory, key, extension, CustomArtworkLimits.MAX_CANVAS_BYTES, source)) {
                is CopyOutcome.Stored -> copied.name
                is CopyOutcome.Rejected -> return copied.result
            }
        if (CustomArtworkKeys.isImageCanvasExtension(extension) && !files.isDecodableImage(directory, name)) {
            files.delete(directory, name)
            return CustomArtworkResult.UNSUPPORTED
        }
        commit(directory, key, name)
        return CustomArtworkResult.SAVED
    }

    /**
     * Streams the picked file into the temporary name of [key] and renames it to its version. The copy stops at
     * [maxBytes] rather than storing an oversized file in full. Every failure path removes the temporary file, and a
     * cancellation does the same before it propagates.
     */
    private suspend fun copyIn(
        directory: CustomArtworkDirectory,
        key: String,
        extension: String?,
        maxBytes: Long,
        source: CustomArtworkSource,
    ): CopyOutcome {
        val temp = CustomArtworkKeys.tempName(key)
        var opened: CustomArtworkReader? = null
        try {
            val reader = source.open() ?: return CopyOutcome.Rejected(CustomArtworkResult.FAILED)
            opened = reader
            val writer = files.openWriter(directory, temp) ?: return CopyOutcome.Rejected(CustomArtworkResult.FAILED)
            // The writer closes even when the copy stops early, so the host can release the file before it is removed.
            val copiedBytes =
                try {
                    pump(reader, writer, maxBytes)
                } finally {
                    writer.close()
                }
            if (copiedBytes == null) {
                files.delete(directory, temp)
                return CopyOutcome.Rejected(CustomArtworkResult.TOO_LARGE)
            }
            if (copiedBytes == 0L) {
                files.delete(directory, temp)
                return CopyOutcome.Rejected(CustomArtworkResult.FAILED)
            }
            val name = CustomArtworkKeys.versionedName(key, nowMillis(), extension)
            if (!files.rename(directory, temp, name)) {
                files.delete(directory, temp)
                return CopyOutcome.Rejected(CustomArtworkResult.FAILED)
            }
            return CopyOutcome.Stored(name)
        } catch (error: CancellationException) {
            files.delete(directory, temp)
            throw error
        } catch (error: Exception) {
            files.delete(directory, temp)
            return CopyOutcome.Rejected(CustomArtworkResult.FAILED)
        } finally {
            opened?.let { closeQuietly(it) }
        }
    }

    /**
     * Copies at most [maxBytes] from [reader] into [writer]. Returns the byte count, or null when the input is longer
     * than the limit. The limit is checked before each write, so the file never holds more than the limit.
     */
    private suspend fun pump(reader: CustomArtworkReader, writer: CustomArtworkWriter, maxBytes: Long): Long? {
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = reader.read(buffer)
            // A zero read ends the input as well as -1, because the reader contract requires it to block for data.
            if (read <= 0) return total
            total += read
            if (total > maxBytes) return null
            writer.write(buffer, read)
        }
    }

    /** Publishes [name] as the current version of [key] and removes the version it replaces. */
    private suspend fun commit(directory: CustomArtworkDirectory, key: String, name: String) {
        stateLock.withLock {
            val current = mutableOverrides.value
            val entries = current.entriesFor(directory)
            val previous = entries[key]
            mutableOverrides.value = current.withEntries(directory, entries + (key to name))
            if (previous != null && previous != name) files.delete(directory, previous)
        }
    }

    /** Removes the current version of [key] from the state and from the folder. False when there was none. */
    private suspend fun remove(directory: CustomArtworkDirectory, key: String): Boolean =
        stateLock.withLock {
            val current = mutableOverrides.value
            val entries = current.entriesFor(directory)
            val name = entries[key] ?: return@withLock false
            mutableOverrides.value = current.withEntries(directory, entries - key)
            files.delete(directory, name)
            true
        }

    /**
     * The newest version of each key in one folder. Leftover temporary and unversioned files are removed, empty files
     * are ignored, and every older version of a key is removed in favour of the newest.
     */
    private suspend fun scan(directory: CustomArtworkDirectory): Map<String, String> {
        val (leftovers, candidates) =
            files.list(directory).partition { CustomArtworkKeys.isTemporaryOrUnversioned(it.name) }
        for (leftover in leftovers) files.delete(directory, leftover.name)
        val result = HashMap<String, String>()
        val byKey = candidates.filter { it.byteLength > 0L }.groupBy { CustomArtworkKeys.keyOf(it.name) }
        for ((key, group) in byKey) {
            val newest = group.maxBy { CustomArtworkKeys.versionOf(it.name) }
            for (file in group) {
                if (file.name != newest.name) files.delete(directory, file.name)
            }
            result[key] = newest.name
        }
        return result
    }

    /** Closes a source after its bytes were read. A source that fails to close cannot change a finished copy. */
    private suspend fun closeQuietly(reader: CustomArtworkReader) {
        try {
            reader.close()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The copy outcome is already decided, so a close failure has nothing left to report.
        }
    }

    private sealed interface CopyOutcome {
        data class Stored(val name: String) : CopyOutcome

        data class Rejected(val result: CustomArtworkResult) : CopyOutcome
    }

    private companion object {
        const val COPY_BUFFER_BYTES = 64 * 1024
    }
}

private fun CustomArtworkOverrides.entriesFor(directory: CustomArtworkDirectory): Map<String, String> =
    when (directory) {
        CustomArtworkDirectory.COVERS -> covers
        CustomArtworkDirectory.CANVASES -> canvases
    }

private fun CustomArtworkOverrides.withEntries(
    directory: CustomArtworkDirectory,
    entries: Map<String, String>,
): CustomArtworkOverrides =
    when (directory) {
        CustomArtworkDirectory.COVERS -> copy(covers = entries)
        CustomArtworkDirectory.CANVASES -> copy(canvases = entries)
    }
