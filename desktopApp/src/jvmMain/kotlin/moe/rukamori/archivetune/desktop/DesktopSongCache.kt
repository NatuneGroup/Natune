package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.shared.data.download.DownloadKey
import moe.rukamori.archivetune.shared.data.download.JvmDownloadRepository
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongDao
import moe.rukamori.archivetune.shared.data.persistence.StoredSongIndex
import moe.rukamori.archivetune.shared.data.persistence.storedOccurrence
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.MaxSongCacheSizeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/** Complete plaintext files only. User downloads live in a different repository and are never evicted here. */
internal class DesktopSongCache(
    directory: Path,
    http: HttpClient,
    private val dao: PortableStoredSongDao,
    private val index: StoredSongIndex,
    private val playback: DesktopPlaybackPort,
    private val preferences: () -> PreferenceSnapshot?,
    private val scope: CoroutineScope,
) {
    private val files = JvmDownloadRepository(directory, http)
    private val gate = Mutex()
    private val pending = HashSet<ContentRef>()
    private fun limit(): Long = preferences()?.get(MaxSongCacheSizeKey, 1024)?.let {
        if (it < 0) Long.MAX_VALUE else it.toLong() * 1024 * 1024
    } ?: 1024L * 1024 * 1024

    fun consider(item: ContentItem, prepared: PreparedAudio) {
        if (item.ref.kind != ContentKind.SONG || prepared.source == AudioSourceType.SPOTIFY || limit() == 0L) return
        synchronized(pending) { if (!pending.add(item.ref)) return }
        scope.launch(Dispatchers.IO) {
            try {
                // One cache fill at a time bounds bandwidth, staging space and decoder preparation.
                gate.withLock {
                    if (fileOf(item.ref.provider, item.ref.id) != null || limit() == 0L) return@withLock
                    val key = DownloadKey(item.ref.id, prepared.source, "", item.ref.provider)
                    val file = files.downloadBytes(key, item) { target ->
                        writeDesktopPreparedDownload(playback, prepared, target, limit())
                    }
                    if (Files.size(file) > limit()) { files.remove(key); return@withLock }
                    index.cacheRemoved(item.ref)
                    index.cached(item, prepared.source, System.currentTimeMillis())
                    trim()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("Automatic song cache fill declined: ${failure.javaClass.simpleName}")
            } finally { synchronized(pending) { pending.remove(item.ref) } }
        }
    }

    suspend fun fileOf(provider: String, id: String): Path? {
        val row = dao.find("CACHE", provider, id) ?: return null
        val source = AudioSourceType.entries.firstOrNull { it.name == row.source } ?: return null
        val file = files.find(DownloadKey(id, source, "", provider))
        if (file == null) index.cacheRemoved(row.storedOccurrence().item.ref)
        return file
    }

    suspend fun remove(ref: ContentRef) = gate.withLock { removeUnlocked(ref) }

    private suspend fun removeUnlocked(ref: ContentRef) {
        val row = dao.find("CACHE", ref.provider, ref.id) ?: return
        index.cacheRemoved(ref)
        val source = AudioSourceType.entries.firstOrNull { it.name == row.source } ?: return
        files.remove(DownloadKey(ref.id, source, "", ref.provider))
    }

    private suspend fun trim() {
        var total = 0L
        for (row in dao.rows("CACHE").sortedByDescending { it.storedAtMs }) {
            val ref = row.storedOccurrence().item.ref
            val file = fileOf(ref.provider, ref.id) ?: continue
            total += Files.size(file)
            if (total > limit()) removeUnlocked(ref)
        }
    }
}
