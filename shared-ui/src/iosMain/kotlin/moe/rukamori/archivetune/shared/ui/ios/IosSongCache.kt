package moe.rukamori.archivetune.shared.ui.ios

import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.rukamori.archivetune.shared.data.download.IosDownloadFiles
import moe.rukamori.archivetune.shared.data.download.IosPreparedDownloadTransfer
import moe.rukamori.archivetune.shared.data.persistence.*
import moe.rukamori.archivetune.shared.data.provider.*
import moe.rukamori.archivetune.shared.domain.download.DownloadTask
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*
import moe.rukamori.archivetune.shared.ui.host.hostSourceSnapshot
import moe.rukamori.archivetune.shared.ui.host.toAudioResolveRequest
import moe.rukamori.archivetune.shared.ui.settings.VaultProviderAccountStore

/** Bounded complete-file prefetch using the shared download preparation/transfer, with a distinct CACHE index. */
internal class IosSongCache(
    private val rows: PortableStoredSongDao,
    root: String,
    http: HttpClient,
    private val settings: SettingsStore,
    private val audio: OrderedAudioResolver,
    accounts: VaultProviderAccountStore,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
    private val report: (String) -> Unit,
) {
    private val files = IosDownloadFiles(root)
    private val index = RoomStoredSongIndex(rows)
    private val cacheIndex = object : StoredSongIndex by index {
        override suspend fun commitDownload(song: ContentItem, source: AudioSourceType, quality: String,
            bytes: Long, completedAtMs: Long, placeFile: suspend () -> Unit): StoredDownload? {
            placeFile()
            index.cached(song, source, completedAtMs)
            val row = requireNotNull(rows.find("CACHE", song.ref.provider, song.ref.id))
            rows.put(row.copy(source = source.name, quality = quality, bytes = bytes))
            return null
        }
    }
    private val transfer = IosPreparedDownloadTransfer(http, files, cacheIndex, nowMs, accounts, accounts)
    private var driver: Job? = null
    suspend fun file(ref: ContentRef, rejected: Set<AudioSourceType> = emptySet()): String? {
        val row = rows.find("CACHE", ref.provider, ref.id) ?: return null
        val source = AudioSourceType.entries.firstOrNull { it.name == row.source } ?: return null
        if (source in rejected) return null
        val pin = SongSourceOverride.get(settings.snapshots.value?.get(SongSourceOverrideKey), ref.id)
        if (pin != null && pin != source) return null
        val size = row.bytes?.takeIf { it > 0 } ?: return null
        val path = files.destinationPath(row.provider, row.mediaId, source, row.quality ?: "cache")
        return path.takeIf { files.exists(it) && files.sizeOf(it) == size }
    }
    suspend fun usedBytes(): Long = rows.rows("CACHE").sumOf { it.bytes ?: 0L }
    suspend fun remove(ref: ContentRef) {
        val row = rows.find("CACHE", ref.provider, ref.id) ?: return
        rows.delete("CACHE", ref.provider, ref.id)
        AudioSourceType.entries.firstOrNull { it.name == row.source }?.let {
            files.removeCommitted(files.destinationPath(row.provider, row.mediaId, it, row.quality ?: "cache"))
        }
    }
    suspend fun clear() { rows.rows("CACHE").forEach { remove(it.storedOccurrence().item.ref) } }
    suspend fun trim() {
        val limitMb = settings.snapshots.value?.get(MaxSongCacheSizeKey) ?: 1024
        if (limitMb < 0) return
        var bytes = usedBytes()
        for (row in rows.rows("CACHE").sortedBy { it.storedAtMs }) {
            if (bytes <= limitMb.toLong() * 1024 * 1024) break
            remove(row.storedOccurrence().item.ref); bytes -= row.bytes ?: 0L
        }
    }
    fun start(queue: StateFlow<QueueState>, player: moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController) {
        driver = scope.launch {
            trim()
            combine(queue, player.snapshot, settings.snapshots) { state, playback, prefs ->
                Triple(state.entries.firstOrNull { it.occurrenceId == playback?.occurrenceId },
                    playback?.playWhenReady == true, prefs?.get(MaxSongCacheSizeKey) ?: 1024)
            }.distinctUntilChanged().collectLatest { (row, playing, limit) ->
                trim()
                if (!playing || limit == 0 || row == null || row.item.ref.kind != ContentKind.SONG ||
                    row.item.ref.provider in setOf("local", "telegram", "spotify_streaming") || file(row.item.ref) != null) return@collectLatest
                val task = DownloadTask("cache:${row.item.ref.provider}:${row.item.ref.id}", row.item.ref, row.item.ref.id,
                    row.item.ref.provider, row.item.title, null, "cache", DownloadPhase.DOWNLOADING, 0L, null, 0L, null, nowMs())
                try {
                    val request = row.toAudioResolveRequest(settings.snapshots.value).copy(purpose = AudioPurpose.DOWNLOAD)
                    val skipped = mutableSetOf(AudioSourceType.SPOTIFY)
                    while (true) {
                        val prepared = (audio.resolve(request, hostSourceSnapshot(settings.snapshots.value), rejected = skipped) as? AudioResolution.Resolved)?.audio ?: break
                        if (transfer.prepare(task, prepared)) {
                            transfer.transferPrepared(task, prepared, 0L, row.item) { bytes, _ ->
                                val cap = settings.snapshots.value?.get(MaxSongCacheSizeKey) ?: 1024
                                check(cap < 0 || bytes <= cap.toLong() * 1024 * 1024) { "Song exceeds cache limit" }
                            }
                            trim(); break
                        }
                        skipped.add(prepared.source)
                    }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (failure: Exception) { report("song cache prefetch failed: ${failure::class.simpleName}")
                } finally { transfer.releasePreparation(task.id); files.discard(task.id) }
            }
        }
    }
    fun close() { driver?.cancel() }
}
