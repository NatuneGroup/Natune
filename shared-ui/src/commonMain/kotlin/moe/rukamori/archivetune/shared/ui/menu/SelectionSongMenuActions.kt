/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu

import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.resources.*

/**
 * Owner-backed batch song rows. Each callback acts on the songs the menu was opened with and throws on
 * failure, so the shared menu reports it instead of a fabricated success.
 */
interface SelectionSongMenuHost {
    /**
     * The baseline batch library command. Adding likes every distinct song and syncs those likes; removing
     * unlikes them and clears their library membership. [songs] is already distinct by media id.
     */
    suspend fun setSongsInLibrary(songs: List<ContentRef>, inLibrary: Boolean)
    /** Queues only the songs that are neither downloaded nor downloading. */
    suspend fun startMissingDownloads(songs: List<ContentOccurrence>)
    suspend fun removeDownloads(songs: List<ContentOccurrence>)
    suspend fun removeFromCache(songs: List<ContentOccurrence>)
    suspend fun removeFromHistory(songs: List<ContentOccurrence>)
}

/**
 * Batch facts. [download] is the aggregate the host derives from every selected song: COMPLETED when all are
 * downloaded, IN_PROGRESS when all are queued, downloading or downloaded, and NOT_DOWNLOADED otherwise.
 */
data class SelectionSongFacts(
    val songs: List<ContentOccurrence>,
    val allInLibrary: Boolean,
    val download: CollectionDownloadState,
    /** True when the selection was opened from the cache list. */
    val fromCache: Boolean,
    /** True when the selection was opened from a history list whose entries can be removed. */
    val historyRemovable: Boolean,
)

/**
 * The batch library, download, cache and history rows of the selection menu, in the baseline order. The
 * remove-download rows ask for confirmation, as the baseline does. Like, queue, playlist and remove-from-playlist
 * rows come from [selectionSongOverflowActions].
 */
fun selectionSongLibraryAndDownloadActions(
    facts: SelectionSongFacts,
    host: SelectionSongMenuHost,
): List<OverflowAction> {
    val enabled = facts.songs.isNotEmpty()
    val distinctRefs = facts.songs.map { it.item.ref }.distinct()
    fun selected(): List<ContentOccurrence> = facts.songs.also { require(it.isNotEmpty()) { "Empty selection" } }
    return buildList {
        add(
            OverflowAction(
                "library_all",
                if (facts.allInLibrary) Res.string.m6_remove_from_library else Res.string.m6_add_to_library,
                enabled,
            ) { host.setSongsInLibrary(distinctRefs, !facts.allInLibrary) },
        )
        when (facts.download) {
            CollectionDownloadState.COMPLETED -> add(
                OverflowAction(
                    "remove_downloads",
                    Res.string.m6_remove_download,
                    enabled,
                    confirm = Res.string.m6_remove_selected_downloads_confirm,
                ) { host.removeDownloads(selected()) },
            )
            CollectionDownloadState.IN_PROGRESS -> add(
                OverflowAction(
                    "downloading",
                    Res.string.m6_downloading,
                    enabled,
                    confirm = Res.string.m6_remove_selected_downloads_confirm,
                ) { host.removeDownloads(selected()) },
            )
            CollectionDownloadState.NOT_DOWNLOADED -> add(
                OverflowAction("download_missing", Res.string.m6_download, enabled) {
                    host.startMissingDownloads(selected())
                },
            )
        }
        if (facts.fromCache) {
            add(OverflowAction("remove_cache_all", Res.string.m6_remove_from_cache, enabled) {
                host.removeFromCache(selected())
            })
        }
        if (facts.historyRemovable) {
            add(OverflowAction("remove_history_all", Res.string.m6_remove_from_history, enabled) {
                host.removeFromHistory(selected())
            })
        }
    }
}
