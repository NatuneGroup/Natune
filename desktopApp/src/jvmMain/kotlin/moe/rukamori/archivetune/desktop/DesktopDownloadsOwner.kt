package moe.rukamori.archivetune.desktop

import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.download.DownloadKey
import moe.rukamori.archivetune.shared.data.download.JvmDownloadRepository
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongDao
import moe.rukamori.archivetune.shared.data.persistence.PortableStoredSongRow
import moe.rukamori.archivetune.shared.data.provider.AudioResolution
import moe.rukamori.archivetune.shared.data.provider.OrderedAudioResolver
import moe.rukamori.archivetune.shared.domain.repository.AudioPurpose
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
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

/**
 * The `downloads` owner on this host, implementing the domain [DownloadActions]. The transfer path is the
 * baseline's: an occurrence's own resolve request with `purpose = DOWNLOAD` runs through the same
 * [OrderedAudioResolver] and the same configured source chain playback uses, and a
 * [PreparedAudio.Progressive] result is committed by [JvmDownloadRepository], which lists the row in the
 * stored-song index before the bytes land. Non-progressive preparations (encrypted or manifest sources)
 * honestly fail rather than write bytes the engine cannot play back. Completed rows play offline: the
 * desktop locator resolver maps the stored file to a local locator before any source resolution.
 */
internal class DesktopDownloadsOwner(
    private val downloads: JvmDownloadRepository,
    private val storedSongs: PortableStoredSongDao,
    private val resolver: OrderedAudioResolver,
    private val library: RoomLibraryRepository,
    private val settings: SettingsStore,
    private val queue: QueueController,
    private val scope: CoroutineScope,
    private val report: (String) -> Unit,
) : DownloadActions {

    /** In-flight and ended transfers the store cannot report yet, keyed by download id. */
    private val jobs = MutableStateFlow<Map<String, TransferState>>(emptyMap())

    private data class TransferState(val ref: ContentRef, val phase: DownloadPhase, val job: Job?)

    override fun observe(): Flow<List<ContentDownload>> =
        combine(storedSongs.observe(DOWNLOAD_ORIGIN), jobs) { rows, transfers ->
            val listed = rows.map { row ->
                val id = downloadId(row.provider, row.mediaId)
                val job = transfers[id]
                ContentDownload(
                    id = id,
                    ref = ContentRef(row.provider, ContentKind.SONG, row.mediaId),
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
                        id = downloadId(transfer.ref.provider, transfer.ref.id),
                        ref = transfer.ref,
                        phase = transfer.phase,
                        bytesDownloaded = 0L,
                        totalBytes = null,
                    )
                }
            listed + extra
        }

    override suspend fun start(ref: ContentRef, quality: String) {
        val id = downloadId(ref.provider, ref.id)
        jobs.value = jobs.value + (id to TransferState(ref, DownloadPhase.QUEUED, null))
        val job = scope.launch { runTransfer(ref, quality) }
        jobs.value = jobs.value + (id to TransferState(ref, DownloadPhase.DOWNLOADING, job))
    }

    /** Stop cancels the transfer: its staged bytes go with the request and no row is written. */
    override suspend fun stop(id: String) {
        val transfer = jobs.value[id] ?: return
        transfer.job?.cancel()
        jobs.value = jobs.value + (id to transfer.copy(phase = DownloadPhase.STOPPED, job = null))
    }

    override suspend fun resume(id: String) {
        val transfer = jobs.value[id] ?: return
        start(transfer.ref, "")
    }

    override suspend fun remove(id: String) {
        val transfer = jobs.value[id]
        transfer?.job?.cancel()
        val (provider, mediaId) = id.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (mediaId.isBlank()) return
        jobs.value = jobs.value + (id to TransferState(
            transfer?.ref ?: ContentRef(provider, ContentKind.SONG, mediaId),
            DownloadPhase.REMOVING, null))
        try {
            val row = storedSongs.find(DOWNLOAD_ORIGIN, provider, mediaId)
            val source = row?.source?.let { name -> AudioSourceType.entries.firstOrNull { it.name == name } }
                ?: AudioSourceType.YOUTUBE
            downloads.remove(DownloadKey(mediaId, source, row?.quality ?: "", provider))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("the download was not removed: ${failure.javaClass.simpleName}")
        } finally {
            jobs.value = jobs.value - id
        }
    }

    private suspend fun runTransfer(ref: ContentRef, quality: String) {
        val id = downloadId(ref.provider, ref.id)
        try {
            val item = library.findSong(ref.id, ref.provider)
                ?: throw IllegalStateException("the song is not in the library")
            val occurrence = ContentOccurrence("download:$id", item)
            val request = occurrence.toAudioResolveRequest(settings.snapshots.value)
                .copy(purpose = AudioPurpose.DOWNLOAD)
            val resolution = resolver.resolve(
                request, hostSourceSnapshot(settings.snapshots.value), rejected = emptySet())
            val audio = (resolution as? AudioResolution.Resolved)?.audio
                ?: throw IllegalStateException("no connected source resolved the song")
            if (audio !is PreparedAudio.Progressive) {
                throw IllegalStateException("the resolved source cannot be stored as a file")
            }
            val key = DownloadKey(
                ref.id, audio.source,
                quality.ifBlank { request.qualities[audio.source] ?: "" },
                ref.provider,
            )
            downloads.download(key, audio, item)
            jobs.value = jobs.value + (id to TransferState(ref, DownloadPhase.COMPLETED, null))
        } catch (cancelled: CancellationException) {
            jobs.value = jobs.value + (id to TransferState(ref, DownloadPhase.STOPPED, null))
            throw cancelled
        } catch (failure: Exception) {
            jobs.value = jobs.value + (id to TransferState(ref, DownloadPhase.FAILED, null))
            report("the download failed: ${failure.message ?: failure.javaClass.simpleName}")
        }
    }

    /**
     * The stored file of a listed download, for offline playback resolution and the media-info file section.
     * The repository only answers when its row is listed and the file holds the committed bytes.
     */
    suspend fun fileOf(provider: String, mediaId: String, source: AudioSourceType, quality: String): Path? =
        downloads.find(DownloadKey(mediaId, source, quality, provider))

    /** Every committed download row, so the page can show each stored item and the locator can
     * resolve a stored song without a network request. */
    fun storedRows(): Flow<List<PortableStoredSongRow>> = storedSongs.observe(DOWNLOAD_ORIGIN)
    suspend fun listedRows() = storedSongs.rows(DOWNLOAD_ORIGIN)

    private fun downloadId(provider: String, mediaId: String) = "$provider:$mediaId"
}
