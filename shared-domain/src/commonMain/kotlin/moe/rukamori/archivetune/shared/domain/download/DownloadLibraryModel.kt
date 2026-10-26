package moe.rukamori.archivetune.shared.domain.download

import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.DownloadPhase
import kotlin.math.roundToInt

/** The collection a section row stands for, fed by the library the scan and library sessions maintain. */
data class DownloadCatalogPlaylist(val id: String, val title: String, val artworkUri: String?, val songIds: List<String>)
data class DownloadCatalogAlbum(val id: String, val title: String, val artists: String, val artworkUri: String?, val songIds: List<String>)

/** One read of the library around the queue; songs absent from the library still list under SONG with task titles. */
data class DownloadCatalog(
    val playlists: List<DownloadCatalogPlaylist> = emptyList(),
    val albums: List<DownloadCatalogAlbum> = emptyList(),
    val songsById: Map<String, ContentItem> = emptyMap(),
)

data class DownloadEntryModel(
    val id: String,
    val title: String,
    val supportingText: String?,
    val artworkUri: String?,
    val songIds: List<String>,
    val progress: Float,
    val percent: Int,
    val speedBytesPerSecond: Long,
    val paused: Boolean,
    val failed: Boolean,
)

data class DownloadSectionModel(
    val type: DownloadMediaKind,
    val entries: List<DownloadEntryModel>,
) {
    val songIds: List<String> get() = entries.flatMap(DownloadEntryModel::songIds).distinct()
    val paused: Boolean get() = entries.isNotEmpty() && entries.all(DownloadEntryModel::paused)
    val percent: Int get() = entries.map { it.progress }.average().times(100).roundToInt().coerceIn(0, 100)
    val speedBytesPerSecond: Long get() = entries.sumOf { it.speedBytesPerSecond }
}

enum class DownloadMediaKind { PLAYLIST, ALBUM, SONG }

data class DownloadLibraryModel(
    val downloadedSections: List<DownloadSectionModel>,
    val progressSections: List<DownloadSectionModel>,
) {
    val isEmpty: Boolean get() = downloadedSections.isEmpty() && progressSections.isEmpty()
}

/**
 * The exact section/progress/speed mapping of the retained `ManageDownloadsUseCase`: a playlist or album row
 * appears once at least [MIN_COLLECTION_SIZE] of its songs are tracked (or completed for the downloaded tab),
 * per-entry progress is the average of per-song fractions, pause means every active entry sits in a paused state,
 * and speeds come from byte samples held for [SPEED_SAMPLE_HOLD_MS].
 */
class DownloadLibraryProjector(private val timeMs: () -> Long) {
    private val byteSamples = mutableMapOf<String, ByteSample>()

    fun project(tasks: List<DownloadTask>, catalog: DownloadCatalog): DownloadLibraryModel {
        val now = timeMs()
        val downloads = tasks.associateBy { it.mediaId }
        val speeds = calculateSpeeds(downloads, now)

        val completedIds = downloads.filterValues { it.phase == DownloadPhase.COMPLETED }.keys
        val activeIds = downloads.filterValues { it.phase.isProgressState() }.keys

        val playlistSongIds = catalog.playlists.associate { it.id to it.songIds }
        val albumSongIds = catalog.albums.associate { it.id to it.songIds }

        val downloadedSections = buildList {
            playlistEntries(catalog, playlistSongIds, completedIds, downloads, speeds, requireCompleted = true)
                .takeIf { it.isNotEmpty() }?.let { add(DownloadSectionModel(DownloadMediaKind.PLAYLIST, it)) }
            albumEntries(catalog, albumSongIds, completedIds, downloads, speeds, requireCompleted = true)
                .takeIf { it.isNotEmpty() }?.let { add(DownloadSectionModel(DownloadMediaKind.ALBUM, it)) }
            songEntries(completedIds, catalog.songsById, downloads, speeds)
                .takeIf { it.isNotEmpty() }?.let { add(DownloadSectionModel(DownloadMediaKind.SONG, it)) }
        }
        val progressSections = buildList {
            playlistEntries(catalog, playlistSongIds, activeIds, downloads, speeds, requireCompleted = false)
                .takeIf { it.isNotEmpty() }?.let { add(DownloadSectionModel(DownloadMediaKind.PLAYLIST, it)) }
            albumEntries(catalog, albumSongIds, activeIds, downloads, speeds, requireCompleted = false)
                .takeIf { it.isNotEmpty() }?.let { add(DownloadSectionModel(DownloadMediaKind.ALBUM, it)) }
            songEntries(activeIds, catalog.songsById, downloads, speeds)
                .takeIf { it.isNotEmpty() }?.let { add(DownloadSectionModel(DownloadMediaKind.SONG, it)) }
        }
        return DownloadLibraryModel(downloadedSections, progressSections)
    }

    private fun playlistEntries(
        catalog: DownloadCatalog, groupSongIds: Map<String, List<String>>, relevantIds: Set<String>,
        downloads: Map<String, DownloadTask>, speeds: Map<String, Long>, requireCompleted: Boolean,
    ): List<DownloadEntryModel> =
        catalog.playlists.mapNotNull { playlist ->
            val songIds = groupSongIds[playlist.id].orEmpty().distinct()
            if (!songIds.isCollectionMatch(relevantIds, downloads, requireCompleted)) return@mapNotNull null
            buildEntry("playlist:${playlist.id}", playlist.title, null, playlist.artworkUri,
                songIds, downloads, speeds)
        }.sortedByDescending { entry -> entry.songIds.maxOfOrNull { downloads[it]?.updateTimeMs ?: 0L } ?: 0L }

    private fun albumEntries(
        catalog: DownloadCatalog, groupSongIds: Map<String, List<String>>, relevantIds: Set<String>,
        downloads: Map<String, DownloadTask>, speeds: Map<String, Long>, requireCompleted: Boolean,
    ): List<DownloadEntryModel> =
        catalog.albums.mapNotNull { album ->
            val songIds = groupSongIds[album.id].orEmpty().distinct()
            if (!songIds.isCollectionMatch(relevantIds, downloads, requireCompleted)) return@mapNotNull null
            buildEntry("album:${album.id}", album.title, album.artists.ifBlank { null }, album.artworkUri,
                songIds, downloads, speeds)
        }.sortedByDescending { entry -> entry.songIds.maxOfOrNull { downloads[it]?.updateTimeMs ?: 0L } ?: 0L }

    private fun songEntries(
        songIds: Set<String>, songsById: Map<String, ContentItem>,
        downloads: Map<String, DownloadTask>, speeds: Map<String, Long>,
    ): List<DownloadEntryModel> =
        songIds.mapNotNull { songId ->
            val download = downloads[songId] ?: return@mapNotNull null
            val song = songsById[songId]
            buildEntry(
                "song:$songId",
                song?.title ?: download.title.ifBlank { songId },
                song?.artists?.joinToString(", ") { it.name }?.ifBlank { null },
                song?.artworkUri,
                listOf(songId), downloads, speeds,
            )
        }.sortedByDescending { downloads[it.songIds.single()]?.updateTimeMs ?: 0L }

    private fun buildEntry(
        id: String, title: String, supportingText: String?, artworkUri: String?,
        songIds: List<String>, downloads: Map<String, DownloadTask>, speeds: Map<String, Long>,
    ): DownloadEntryModel {
        val entries = songIds.mapNotNull(downloads::get)
        val activeEntries = entries.filter { it.phase.isProgressState() }
        val progress = songIds.map { songId ->
            val download = downloads[songId] ?: return@map 0f
            if (download.phase == DownloadPhase.COMPLETED) 1f
            else download.totalBytes?.takeIf { it > 0 }?.let {
                (download.bytesDownloaded.toFloat() / it).coerceIn(0f, 1f)
            } ?: 0f
        }.average().toFloat().coerceIn(0f, 1f)
        return DownloadEntryModel(
            id = id, title = title, supportingText = supportingText, artworkUri = artworkUri,
            songIds = songIds, progress = progress,
            percent = (progress * 100f).roundToInt().coerceIn(0, 100),
            speedBytesPerSecond = activeEntries.sumOf { speeds[it.id] ?: 0L },
            paused = activeEntries.isNotEmpty() && activeEntries.all { it.phase.isPausedState() },
            failed = activeEntries.any { it.phase == DownloadPhase.FAILED },
        )
    }

    private fun calculateSpeeds(downloads: Map<String, DownloadTask>, now: Long): Map<String, Long> {
        byteSamples.keys.retainAll(downloads.keys)
        return downloads.mapValues { (songId, download) ->
            val previous = byteSamples[songId]
            val currentBytes = download.bytesDownloaded.coerceAtLeast(0L)
            if (download.phase != DownloadPhase.DOWNLOADING || previous == null || currentBytes < previous.bytes) {
                byteSamples[songId] = ByteSample(currentBytes, now, 0L)
                return@mapValues 0L
            }
            if (currentBytes == previous.bytes) {
                return@mapValues if (now - previous.timestampMs <= SPEED_SAMPLE_HOLD_MS) {
                    previous.speedBytesPerSecond
                } else 0L
            }
            val elapsedMs = (now - previous.timestampMs).coerceAtLeast(1L)
            val speed = (currentBytes - previous.bytes) * 1_000L / elapsedMs
            byteSamples[songId] = ByteSample(currentBytes, now, speed)
            speed
        }
    }

    private fun List<String>.isCollectionMatch(
        relevantIds: Set<String>, downloads: Map<String, DownloadTask>, requireCompleted: Boolean,
    ): Boolean {
        if (size < MIN_COLLECTION_SIZE || none(relevantIds::contains)) return false
        return if (requireCompleted) {
            all { downloads[it]?.phase == DownloadPhase.COMPLETED }
        } else {
            all { downloads[it]?.phase?.isTrackedState() == true }
        }
    }

    private fun DownloadPhase.isTrackedState(): Boolean =
        this == DownloadPhase.COMPLETED || isProgressState()

    private fun DownloadPhase.isProgressState(): Boolean =
        this == DownloadPhase.QUEUED || this == DownloadPhase.DOWNLOADING ||
            this == DownloadPhase.STOPPED || this == DownloadPhase.FAILED

    private fun DownloadPhase.isPausedState(): Boolean =
        this == DownloadPhase.STOPPED || this == DownloadPhase.FAILED

    private data class ByteSample(val bytes: Long, val timestampMs: Long, val speedBytesPerSecond: Long)

    companion object {
        const val MIN_COLLECTION_SIZE = 2
        const val SPEED_SAMPLE_HOLD_MS = 2_500L
    }
}
