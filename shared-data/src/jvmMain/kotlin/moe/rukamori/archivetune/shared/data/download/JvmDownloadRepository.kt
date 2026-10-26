/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.download

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import moe.rukamori.archivetune.shared.data.persistence.ListedDownload
import moe.rukamori.archivetune.shared.data.persistence.StoredSongIndex
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio

/** The file a request stages its bytes in, inside the request's own staging directory. */
private const val STAGING_FILE = "download.part"

/**
 * The identity of one download. [provider] names the provider that owns [mediaId], because two providers can name
 * the same media id. A write takes the provider from its song when the key has none. Reads and removals need the
 * provider, so a key without one is never published or removed.
 */
data class DownloadKey(
    val mediaId: String,
    val source: AudioSourceType,
    val quality: String,
    val provider: String? = null,
)

/**
 * Where the file of a listed download stands. Only [COMPLETE] is a download, and no other state is ever published.
 * [MISMATCHED] is a file at the download's own path that does not hold the committed bytes. It is removed with its
 * row. [UNPROVEN] is a row with no committed size, so it is never published. [MISSING] and [NOT_REGULAR] mean the
 * row's file is absent or is not a regular file.
 */
private enum class DownloadFileState { COMPLETE, MISSING, NOT_REGULAR, UNPROVEN, MISMATCHED }

/**
 * User downloads have NO automatic evictor; only explicit remove deletes a committed object. Each file is named by
 * its provider, source, media id and quality, so two providers never share bytes. When [index] is given, a commit
 * lists the row before its bytes move into place. A failed or cancelled commit restores the listing it displaced.
 * A removal drops the row before the file, so a row never names a file that is missing. A download is published
 * only while its row is listed and its file holds the committed byte count. [reconcile] applies that test
 * to every listed row.
 *
 * The transfer uses the client it was given and never the player's stream cache, so no playback cache eviction can
 * touch a download. Each request stages its bytes in a directory that only that request owns, and removes it when the
 * request ends, whether it commits, fails or is cancelled.
 */
class JvmDownloadRepository(
    root: Path,
    private val client: HttpClient,
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val index: StoredSongIndex? = null,
) {
    private val root = root.toAbsolutePath().normalize()
    private val mutex = Mutex()
    private val owners = mutableMapOf<DownloadKey, String>()
    init { require(maxBytes > 0) }

    /** An encrypted/segmented provider response must never be committed as a progressive file. */
    suspend fun download(key: DownloadKey, prepared: PreparedAudio, song: ContentItem? = null): Path {
        require(prepared.source == key.source) { "Download source mismatch" }
        require(prepared is PreparedAudio.Progressive) { "Native decryption/manifest preparation required" }
        return download(key, prepared.stream.uri, song)
    }

    /**
     * [song] is the item the file holds. The index lists the download by it, so an indexed repository needs it, and
     * it must be the song the media id names. Its provider owns the file when the key does not name one.
     */
    suspend fun download(key: DownloadKey, resolvedUrl: String, song: ContentItem? = null): Path {
        require(key.mediaId.isNotBlank())
        require(key.source != AudioSourceType.SPOTIFY) { "Spotify streaming is not a download source" }
        require(resolvedUrl.startsWith("https://")) { "Download requires a resolved HTTPS source" }
        require(index == null || song != null) { "An indexed download needs the song it stores" }
        require(song == null || (song.ref.kind == ContentKind.SONG && song.ref.id == key.mediaId)) {
            "Download song does not match its media id"
        }
        val provider = requireNotNull(key.provider ?: song?.ref?.provider) { "A download names its provider" }
        require(song == null || song.ref.provider == provider) { "Download provider does not match its song" }
        val owned = key.copy(provider = provider)
        val token = UUID.randomUUID().toString()
        // Only this request owns its staging directory, so the request removes exactly what it staged.
        val requestDirectory = root.resolve("staging").resolve("request_$token")
        val staging = requestDirectory.resolve(STAGING_FILE)
        val destination = path(provider, owned)
        var committedBytes = 0L
        mutex.withLock { owners[owned] = token }
        try {
            withContext(Dispatchers.IO) {
                Files.createDirectories(requestDirectory)
                Files.createDirectories(destination.parent)
                client.prepareGet(resolvedUrl).execute { response ->
                    require(response.status.value in 200..299) { "Download HTTP ${response.status.value}" }
                    val channel = response.bodyAsChannel()
                    Files.newOutputStream(staging, StandardOpenOption.CREATE_NEW).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer)
                            if (count < 0) break
                            if (count == 0) { yield(); continue }
                            total += count
                            require(total <= maxBytes) { "Download exceeds size limit" }
                            output.write(buffer, 0, count)
                        }
                        require(total > 0) { "Empty download" }
                        committedBytes = total
                    }
                }
                // The bytes reach the disk before the rename publishes the file. Otherwise a crash could leave a file
                // of the committed size whose blocks were never written.
                FileChannel.open(staging, StandardOpenOption.WRITE).use { it.force(true) }
                currentCoroutineContext().ensureActive()
                mutex.withLock {
                    if (owners[owned] != token) throw CancellationException("Download request superseded")
                    // No non-atomic fallback: a filesystem without atomic rename must fail honestly.
                    val placeFile: suspend () -> Unit = {
                        currentCoroutineContext().ensureActive()
                        Files.move(
                            staging,
                            destination,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    }
                    // The row is listed before the bytes move, so a failed move restores the listing it displaced.
                    val displaced = if (index != null && song != null) {
                        index.commitDownload(
                            song,
                            key.source,
                            key.quality,
                            committedBytes,
                            System.currentTimeMillis(),
                            placeFile,
                        )
                    } else {
                        placeFile()
                        null
                    }
                    owners.remove(owned)
                    // The earlier download of this song was another file, which no row names any more. The new
                    // download is already listed, so a file that cannot be removed only wastes space.
                    if (displaced != null) {
                        val earlier = DownloadKey(key.mediaId, displaced.source, displaced.quality, provider)
                        try {
                            Files.deleteIfExists(path(provider, earlier))
                        } catch (leftover: IOException) {
                            // The committed download stands. Its orphaned earlier file is only wasted space.
                        }
                    }
                }
            }
            return destination
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    deleteRequestDirectory(requestDirectory)
                } catch (leftover: IOException) {
                    // A leftover staging file is only wasted space. It is never published, and the outcome stands.
                }
                mutex.withLock { if (owners[owned] == token) owners.remove(owned) }
            }
        }
    }

    /**
     * The file of [key] when it is a download. A key without its provider names none. With an index, the row
     * must be listed and the file must hold the committed byte count, so an unlisted file is never published.
     * Without an index only the file's presence is checked. Real I/O failures propagate, and only an absent file
     * reads as no download.
     */
    suspend fun find(key: DownloadKey): Path? = withContext(Dispatchers.IO) {
        val provider = key.provider ?: return@withContext null
        val file = path(provider, key)
        val listing = index ?: return@withContext file.takeIf { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        mutex.withLock {
            val listed = listing.listedDownload(provider, key.mediaId, key.source, key.quality)
            file.takeIf { listed != null && fileState(it, listed.bytes) == DownloadFileState.COMPLETE }
        }
    }

    /** Removes the download of [key]. The row goes first, so no listed row names a missing file. */
    suspend fun remove(key: DownloadKey) {
        val provider = requireNotNull(key.provider) { "A download names its provider to be removed" }
        withContext(Dispatchers.IO) {
            mutex.withLock {
                owners.remove(key.copy(provider = provider))
                // The row goes first: a row that outlived its file would list a download that is not there.
                index?.downloadRemoved(provider, key.mediaId, key.source, key.quality)
                Files.deleteIfExists(path(provider, key))
            }
        }
    }

    /**
     * Checks every listed download against its own file, at process start and after the download folder may have
     * changed outside the app. A row is dropped when its file is missing, is not a regular file, or does not hold
     * its committed size, and a file that does not hold its committed bytes is removed with its row. A row written
     * before sizes were kept is adopted when its earlier file is a whole file that no other listed row names.
     * Otherwise its row is dropped and the file is left alone, because it may belong to another provider.
     * Cancellation and real I/O failures propagate, so a failed check never drops a row by mistake. Returns how many
     * rows were dropped. Without an index there is nothing to reconcile, and the result is zero.
     */
    suspend fun reconcile(): Int {
        val listing = index ?: return 0
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val listed = listing.listedDownloads()
                // Every listed row counts, so an earlier file that two rows could have written is never adopted.
                val claimants = listed.groupingBy { earlierPath(keyOf(it)) }.eachCount()
                var dropped = 0
                for (row in listed) {
                    currentCoroutineContext().ensureActive()
                    val key = keyOf(row)
                    val committed = row.bytes
                    if (committed == null) {
                        val earlier = earlierPath(key)
                        val size = if (claimants[earlier] == 1) completedSize(earlier) else null
                        if (size == null) {
                            listing.downloadRemoved(row.provider, row.mediaId, row.source, row.quality)
                            dropped++
                        } else {
                            Files.createDirectories(path(row.provider, key).parent)
                            Files.move(
                                earlier,
                                path(row.provider, key),
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING,
                            )
                            listing.adoptDownloadBytes(row.provider, row.mediaId, row.source, row.quality, size)
                        }
                        continue
                    }
                    val state = fileState(path(row.provider, key), committed)
                    if (state == DownloadFileState.COMPLETE) continue
                    // The row goes first, as in remove, so no listed row names a file that is about to go.
                    listing.downloadRemoved(row.provider, row.mediaId, row.source, row.quality)
                    if (state == DownloadFileState.MISMATCHED) Files.deleteIfExists(path(row.provider, key))
                    dropped++
                }
                dropped
            }
        }
    }

    /**
     * The byte count of [file] when it is a regular file of at least one byte whose last byte reads back. The result
     * is null when the file is absent, empty or not a regular file. Other I/O failures propagate.
     */
    private fun completedSize(file: Path): Long? {
        val attributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (absent: NoSuchFileException) {
            return null
        }
        val size = attributes.size()
        if (!attributes.isRegularFile || size <= 0) return null
        val lastByteReads = Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use {
            it.position(size - 1)
            it.read(ByteBuffer.allocate(1)) == 1
        }
        return size.takeIf { lastByteReads }
    }

    /**
     * The state of [file] against the committed byte count of its row. Presence is read from the file itself,
     * never from the row. The size must match, and the last committed byte must read back. The content is not
     * re-hashed, because reading every download again at each start is too costly. Each download is synced
     * before it is published. Only an absent file is reported as missing, and every other I/O failure propagates.
     */
    private fun fileState(file: Path, bytes: Long?): DownloadFileState {
        val attributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (absent: NoSuchFileException) {
            return DownloadFileState.MISSING
        }
        if (!attributes.isRegularFile) return DownloadFileState.NOT_REGULAR
        if (bytes == null || bytes <= 0) return DownloadFileState.UNPROVEN
        if (attributes.size() != bytes) return DownloadFileState.MISMATCHED
        return Files.newByteChannel(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
            channel.position(bytes - 1)
            val lastByte = channel.read(ByteBuffer.allocate(1))
            if (lastByte == 1) DownloadFileState.COMPLETE else DownloadFileState.MISMATCHED
        }
    }

    /**
     * Removes what one request staged, then the request's own directory. It only lists that directory, which the
     * request named with its random token, so nothing outside the request is touched.
     */
    private fun deleteRequestDirectory(directory: Path) {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return
        Files.newDirectoryStream(directory).use { entries ->
            for (entry in entries) Files.deleteIfExists(entry)
        }
        Files.deleteIfExists(directory)
    }

    /** The file of [key] under [provider], so providers that share a media id never share bytes. */
    private fun path(provider: String, key: DownloadKey): Path =
        pathUnder(key.source, sha256(listOf(provider, key.source.name, key.mediaId, key.quality)))

    /**
     * The name a download had before its provider was part of it. Every provider that listed the same source,
     * media id and quality shares that name, so reconciliation adopts such a file only when no other row names it.
     */
    private fun earlierPath(key: DownloadKey): Path =
        pathUnder(key.source, sha256(listOf(key.source.name, key.mediaId, key.quality)))

    private fun pathUnder(source: AudioSourceType, hash: String): Path =
        root.resolve("downloads").resolve(source.name.lowercase()).resolve("$hash.media")

    private fun sha256(parts: List<String>): String {
        val identity = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun keyOf(row: ListedDownload): DownloadKey =
        DownloadKey(row.mediaId, row.source, row.quality, row.provider)
}
