package moe.rukamori.archivetune.desktop

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import moe.rukamori.archivetune.shared.data.download.DownloadKey
import moe.rukamori.archivetune.shared.data.download.JvmDownloadRepository
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongDao
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongRow
import moe.rukamori.archivetune.shared.data.persistence.storedOccurrence
import moe.rukamori.archivetune.shared.data.provider.AudioResolution
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.domain.repository.AudioPurpose
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.DownloadActions
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.repository.QueueController
import moe.rukamori.archivetune.shared.data.persistence.RoomLibraryRepository
import moe.rukamori.archivetune.shared.domain.settings.SettingsStore
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.host.hostSourceSnapshot
import moe.rukamori.archivetune.shared.ui.host.toAudioResolveRequest

/** The origin a committed download row carries, the `StoredSongOrigin.DOWNLOAD` schema value. */
private const val DOWNLOAD_ORIGIN = "DOWNLOAD"

/** Transfers prepared plaintext through the player's existing admitted/decrypting routes and atomic download store. */
internal class DesktopDownloadsOwner(
    private val downloads: JvmDownloadRepository,
    private val storedSongs: PortableStoredSongDao,
    private val resolver: OrderedAudioResolver,
    private val library: RoomLibraryRepository,
    private val settings: SettingsStore,
    private val queue: QueueController,
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
    private val playback: moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort,
    private val exportTags: suspend (ContentItem, Path) -> Unit = { _, _ -> },
) : DownloadActions {

    private val transfers = DesktopDownloadTransfers(scope, ::runTransfer, report)

    override fun observe(): Flow<List<ContentDownload>> =
        combine(storedSongs.observe(DOWNLOAD_ORIGIN), transfers.state) { rows, transfers ->
            val listed = rows.map { row ->
                val ref = row.storedOccurrence().item.ref
                val id = ref.desktopDownloadId()
                val job = transfers[id]
                ContentDownload(
                    id = id,
                    ref = ref,
                    phase = job?.phase ?: DownloadPhase.COMPLETED,
                    bytesDownloaded = row.bytes ?: 0L,
                    totalBytes = row.bytes,
                )
            }
            val extra = transfers.values
                .filter { transfer ->
                    transfer.phase != DownloadPhase.COMPLETED &&
                        listed.none { it.ref == transfer.ref }
                }
                .map { transfer ->
                    ContentDownload(
                        id = transfer.ref.desktopDownloadId(),
                        ref = transfer.ref,
                        phase = transfer.phase,
                        bytesDownloaded = 0L,
                        totalBytes = null,
                    )
                }
            listed + extra
        }

    override suspend fun start(ref: ContentRef, quality: String) {
        val item = library.findSong(ref.id, ref.provider)
            ?: queue.state.value.entries.firstOrNull { it.item.ref == ref }?.item
            ?: throw IllegalStateException("The song metadata is not available")
        start(item, quality)
    }

    /** A catalog menu already has canonical metadata; downloading it must not require saving it to the library first. */
    suspend fun start(item: ContentItem, quality: String = "") {
        require(desktopDownloadSupported(item.ref)) { "This item cannot be downloaded on the desktop host" }
        transfers.start(item, quality)
    }

    /** Stop cancels the transfer: its staged bytes go with the request and no row is written. */
    override suspend fun stop(id: String) {
        transfers.stop(id)
    }

    override suspend fun resume(id: String) {
        transfers.resume(id)
    }

    override suspend fun remove(id: String) {
        val (provider, mediaId) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (mediaId.isBlank()) return
        try {
            transfers.remove(ContentRef(provider, ContentKind.SONG, mediaId)) {
                val row = storedSongs.find(DOWNLOAD_ORIGIN, provider, mediaId)
                val source = row?.source?.let { name -> AudioSourceType.entries.firstOrNull { it.name == name } }
                    ?: AudioSourceType.YOUTUBE
                downloads.remove(DownloadKey(mediaId, source, row?.quality ?: "", provider))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("the download was not removed: ${failure.javaClass.simpleName}")
            throw failure
        }
    }

    private suspend fun runTransfer(item: ContentItem, quality: String) {
        val ref = item.ref
        val id = ref.desktopDownloadId()
        val occurrence = ContentOccurrence("download:$id", item)
        val request = occurrence.toAudioResolveRequest(settings.snapshots.value)
            .copy(purpose = AudioPurpose.DOWNLOAD)
        val resolution = resolver.resolve(
            request, desktopSourceSnapshot(settings.snapshots.value), rejected = emptySet())
        val audio = (resolution as? AudioResolution.Resolved)?.audio
            ?: throw IllegalStateException("no connected source resolved the song")
        val key = DownloadKey(
            ref.id, audio.source,
            quality.ifBlank { request.qualities[audio.source] ?: "" },
            ref.provider,
        )
        downloads.downloadBytes(key, item) { file -> writeDesktopPreparedDownload(playback, audio, file) }
    }

    /**
     * The stored file of a listed download, for offline playback resolution and the media-info file section.
     * The repository only answers when its row is listed and the file holds the committed bytes.
     */
    suspend fun fileOf(provider: String, mediaId: String, source: AudioSourceType, quality: String): Path? =
        downloads.find(DownloadKey(mediaId, source, quality, provider))

    suspend fun completedFile(ref: ContentRef): DesktopCompletedDownload? {
        if (ref.kind !in setOf(ContentKind.SONG, ContentKind.EPISODE)) return null
        val row = storedSongs.find(DOWNLOAD_ORIGIN, ref.provider, ref.id) ?: return null
        val source = AudioSourceType.entries.firstOrNull { it.name == row.source } ?: return null
        val quality = row.quality ?: return null
        val bytes = row.bytes?.takeIf { it > 0L } ?: return null
        val file = downloads.find(DownloadKey(ref.id, source, quality, ref.provider)) ?: return null
        return DesktopCompletedDownload(file, bytes)
    }

    suspend fun export(ref: ContentRef, destination: Path) {
        val completed = completedFile(ref) ?: throw IllegalStateException("No completed download is available")
        val item = library.findSong(ref.id, ref.provider)
            ?: storedSongs.find(DOWNLOAD_ORIGIN, ref.provider, ref.id)?.storedOccurrence()?.item
            ?: error("Downloaded metadata is missing")
        copyDesktopDownload(completed, destination) { file -> exportTags(item, file) }
    }

    /** Every committed download row, so the page can show each stored item and the locator can
     * resolve a stored song without a network request. */
    fun storedRows(): Flow<List<PortableStoredSongRow>> = storedSongs.observe(DOWNLOAD_ORIGIN)
    suspend fun listedRows() = storedSongs.rows(DOWNLOAD_ORIGIN)

}
