package moe.rukamori.archivetune.shared.ui.menu

import androidx.compose.runtime.Composable
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.LibraryRepository
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * Owner-backed song rows from the baseline song menu that are not player or queue controls. Radio, library
 * membership, speed dial, history, cache, downloads, cover files, metadata refetch and navigation stay with
 * their owners. A failed callback throws so the shared menu reports the failure instead of a fake success.
 */
interface SongMenuHost {
    /** Starts the song's YouTube radio. The shared menu offers it only for catalog songs that are not Telegram. */
    suspend fun startRadio(song: ContentRef)
    /** Library membership is a separate host-owned flag. It is never a [moe.rukamori.archivetune.shared.domain.repository.LibraryMutation.Like]. */
    suspend fun setInLibrary(song: ContentRef, inLibrary: Boolean)
    suspend fun setSpeedDialPinned(song: ContentRef, pinned: Boolean)
    /** Removes this one listening-history entry, not every play of the song. */
    suspend fun removeFromHistory(song: ContentRef)
    suspend fun removeFromCache(song: ContentRef)
    /** Clears stale failed or queued partial data first, then queues a fresh download. */
    suspend fun startDownload(song: ContentRef)
    /** Cancels a queued or running download, or removes a completed one. */
    suspend fun removeDownload(song: ContentRef)
    /** Writes a completed download through a host destination picker. [suggestedName] is already sanitised. */
    suspend fun exportDownload(song: ContentRef, suggestedName: String)
    /** Hands the watch URL to the configured external downloader. Returns false when no app accepted it. */
    suspend fun openWithExternalDownloader(song: ContentRef): Boolean
    /** Opens the album route for [albumId]. */
    suspend fun viewAlbum(albumId: String)
    /** Opens the host's sleep-timer sheet for the playing song. */
    fun openSleepTimer()
    suspend fun setRecommendationBlocked(song: ContentRef, blocked: Boolean)
    /** Saves the song artwork to the user's pictures folder. Returns false when the save fails. */
    suspend fun saveCoverArtwork(song: ContentRef, artworkUrl: String): Boolean
    /** Re-reads the song from the provider and commits the newer metadata over the stored record. */
    suspend fun refetchSong(song: ContentRef)
    /** Opens the host's track-info and specs sheet. */
    fun openTrackInfo(song: ContentRef)
}

/**
 * Facts the caller already holds for one song row. The shared menu reads no download, cache or history
 * storage itself. Each value records what the host observed, so a row appears only where the baseline shows it.
 */
data class SongMenuFacts(
    val song: ContentRef,
    val title: String,
    val isLocal: Boolean,
    val isTelegram: Boolean,
    val inLibrary: Boolean,
    val speedDialPinned: Boolean,
    val download: CollectionDownloadState,
    /** Lowercase extension of the completed download, used only when [download] is COMPLETED. */
    val downloadExtension: String?,
    /** True when the menu was opened from a listening-history row. */
    val fromHistory: Boolean,
    /** True when the menu was opened from the cache list. */
    val fromCache: Boolean,
    val albumId: String?,
    val artworkUrl: String?,
    val recommendationBlocked: Boolean,
    /** Whether the user enabled an external downloader in settings. */
    val externalDownloaderEnabled: Boolean,
    /** Whether the enabled downloader has a package name configured. A row without one is disabled, not a no-op. */
    val externalDownloaderConfigured: Boolean,
)

/**
 * The baseline export file name: the title with path-unsafe characters replaced, falling back to "audio",
 * followed by the completed download's extension.
 */
fun songExportFileName(title: String, extension: String): String {
    val safeTitle = title.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "audio" }
    return "$safeTitle.$extension"
}

/**
 * Library, speed-dial, history, cache, download, navigation and maintenance rows of the single-song menu, in
 * the baseline order. Share, queue, like and artist rows come from [songOverflowActions]. Local files get only
 * the rows the baseline shows for them.
 */
fun songLibraryAndDownloadActions(facts: SongMenuFacts, host: SongMenuHost): List<OverflowAction> {
    val song = facts.song
    return buildList {
        if (!facts.isLocal && !facts.isTelegram) {
            add(OverflowAction("start_radio", Res.string.m6_start_radio) { host.startRadio(song) })
        }
        if (!facts.isLocal) {
            add(
                OverflowAction(
                    "library",
                    if (facts.inLibrary) Res.string.m6_remove_from_library else Res.string.m6_add_to_library,
                ) { host.setInLibrary(song, !facts.inLibrary) },
            )
        }
        add(
            OverflowAction(
                "speed_dial",
                if (facts.speedDialPinned) Res.string.m6_remove_speed_dial else Res.string.m6_pin_speed_dial,
            ) { host.setSpeedDialPinned(song, !facts.speedDialPinned) },
        )
        if (facts.fromHistory) {
            add(OverflowAction("remove_history", Res.string.m6_remove_from_history) { host.removeFromHistory(song) })
        }
        if (facts.fromCache) {
            add(OverflowAction("remove_cache", Res.string.m6_remove_from_cache) { host.removeFromCache(song) })
        }
        if (!facts.isLocal) {
            when (facts.download) {
                CollectionDownloadState.COMPLETED ->
                    add(OverflowAction("remove_download", Res.string.m6_remove_download) { host.removeDownload(song) })
                CollectionDownloadState.IN_PROGRESS ->
                    add(OverflowAction("downloading", Res.string.m6_downloading) { host.removeDownload(song) })
                CollectionDownloadState.NOT_DOWNLOADED ->
                    add(OverflowAction("download", Res.string.m6_download) { host.startDownload(song) })
            }
            val extension = facts.downloadExtension
            if (facts.download == CollectionDownloadState.COMPLETED && extension != null) {
                add(
                    OverflowAction("export_download", Res.string.m6_export_download) {
                        host.exportDownload(song, songExportFileName(facts.title, extension))
                    },
                )
            }
            if (facts.externalDownloaderEnabled) {
                add(
                    OverflowAction(
                        "open_downloader",
                        Res.string.m6_open_with_downloader,
                        facts.externalDownloaderConfigured,
                    ) { check(host.openWithExternalDownloader(song)) { "External downloader unavailable" } },
                )
            }
        }
        val albumId = facts.albumId
        if (albumId != null) {
            add(OverflowAction("view_album", Res.string.m6_view_album) { host.viewAlbum(albumId) })
        }
        add(OverflowAction("sleep_timer", Res.string.m6_sleep_timer) { host.openSleepTimer() })
        if (!facts.isLocal) {
            add(
                OverflowAction(
                    "recommendation_block",
                    if (facts.recommendationBlocked) Res.string.m6_undo_dont_recommend_song else Res.string.m6_dont_recommend_song,
                ) { host.setRecommendationBlocked(song, !facts.recommendationBlocked) },
            )
        }
        add(
            OverflowAction("download_cover", Res.string.m6_download_cover, !facts.artworkUrl.isNullOrBlank()) {
                check(host.saveCoverArtwork(song, requireNotNull(facts.artworkUrl))) { "Cover save failed" }
            },
        )
        if (!facts.isLocal) {
            add(OverflowAction("refetch", Res.string.m6_refetch) { host.refetchSong(song) })
        }
        add(OverflowAction("track_info", Res.string.m6_track_info) { host.openTrackInfo(song) })
    }
}

/**
 * The single-song menu body. It reuses the collection playlist chooser, so "Add to playlist" commits the
 * occurrence the menu was opened with, not the media id. [actions] is the caller's combined list, for
 * example songOverflowActions(...) + songLibraryAndDownloadActions(...).
 */
@Composable
fun SongOverflowMenu(
    identity: String,
    expanded: Boolean,
    selected: ContentOccurrence,
    actions: List<OverflowAction>,
    library: LibraryRepository,
    editablePlaylists: List<ContentItem>,
    onDismiss: () -> Unit,
    onFailure: () -> Unit,
) = OccurrenceOverflowMenu(
    identity, expanded, listOf(selected), actions, library, editablePlaylists, onDismiss, onFailure,
)
