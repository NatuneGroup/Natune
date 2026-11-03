package moe.rukamori.archivetune.desktop

import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.HistoryOrigin
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.BlockedSongRef
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPin
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialPinType
import moe.rukamori.archivetune.shared.domain.settings.SpeedDialSongIdsKey
import moe.rukamori.archivetune.shared.domain.settings.parseBlockedSongRefs
import moe.rukamori.archivetune.shared.domain.settings.parseSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.serializeBlockedSongRefs
import moe.rukamori.archivetune.shared.domain.settings.serializeSpeedDialPins
import moe.rukamori.archivetune.shared.domain.settings.toggleBlockedSongRef
import moe.rukamori.archivetune.shared.ui.menu.SongMenuHost
import moe.rukamori.archivetune.shared.ui.menu.songExportFileName
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes

/** Captures the opened occurrence: a slow action must not act on a subsequently opened menu. */
internal class DesktopSongMenuHost(
    private val state: DesktopItemMenuState,
    private val graph: DesktopSessionGraph,
    private val selected: ContentOccurrence,
    private val sleepSheet: DesktopSleepTimerSheetState,
    private val openRoute: (String) -> Unit,
) : SongMenuHost {
    override suspend fun startRadio(song: ContentRef) {
        if (!song.supportsDesktopSongPin()) state.unavailable("content_menu_radio_unsupported")
        // The radio owner keeps a current-song start seamless and locks the seed comparison against queue advances.
        graph.radio.start(song, selected.setVideoId)
    }

    override suspend fun setInLibrary(song: ContentRef, inLibrary: Boolean) {
        require(song == selected.item.ref)
        if (inLibrary) {
            graph.library.saveItems(listOf(selected.item))
        } else {
            graph.library.removeItems(listOf(song))
        }
    }

    override suspend fun setSpeedDialPinned(song: ContentRef, pinned: Boolean) {
        if (!song.supportsDesktopSongPin()) state.unavailable("content_menu_pin_unsupported")
        graph.settings.edit {
            val pins = parseSpeedDialPins(get(SpeedDialSongIdsKey).orEmpty())
            val next = desktopSongPins(pins, song.id, pinned)
                ?: state.unavailable("content_menu_pin_full")
            set(SpeedDialSongIdsKey, serializeSpeedDialPins(next))
        }
    }

    override suspend fun removeFromHistory(song: ContentRef) {
        val id = selected.occurrenceId.takeIf { it.startsWith("history:") }?.removePrefix("history:")
            ?: state.unavailable("content_menu_history_unsupported")
        graph.pageOwners.history.remove(HistoryOrigin.LOCAL, setOf(id))
    }

    override suspend fun removeFromCache(song: ContentRef) = state.unavailable("content_menu_cache_unsupported")

    override suspend fun startDownload(song: ContentRef) {
        if (song.kind != ContentKind.SONG) state.unavailable("content_menu_episode_download_unsupported")
        // A catalog row already carries canonical metadata; downloading it must not require saving it first.
        graph.downloads.start(selected.item)
    }

    override suspend fun removeDownload(song: ContentRef) {
        graph.downloads.remove(song.songDownloadId())
        check(graph.downloads.listedRows().none { it.provider == song.provider && it.mediaId == song.id })
    }

    override suspend fun exportDownload(song: ContentRef, suggestedName: String) {
        // The owner resolves, size-checks and stages the committed download before replacing the destination.
        graph.downloads.completedFile(song) ?: state.unavailable("content_menu_download_missing")
        val file = chooseDesktopSaveFile(DesktopStrings["menu_download_export_pick"], suggestedName) ?: return
        graph.downloads.export(song, file.toPath())
    }

    override suspend fun openWithExternalDownloader(song: ContentRef): Boolean =
        state.unavailable("content_menu_external_downloader_unsupported")

    override suspend fun viewAlbum(albumId: String) {
        val kind = if (selected.item.ref.kind == ContentKind.EPISODE) ContentKind.PODCAST else ContentKind.ALBUM
        openRoute(ArchiveRoutes.content(ContentRef(selected.item.ref.provider, kind, albumId)))
    }

    override fun openSleepTimer() { sleepSheet.open = true }

    /**
     * The block list the baseline kept as a timestamp on the stored song row is a preference here, so it
     * survives without a library row and exports with SETTINGS. Recommendations come from YouTube feeds,
     * so the same gate as pins and refetches applies.
     */
    override suspend fun setRecommendationBlocked(song: ContentRef, blocked: Boolean) {
        if (!song.supportsDesktopSongPin()) state.unavailable("content_menu_recommendation_unsupported")
        graph.settings.edit {
            val next = toggleBlockedSongRef(
                parseBlockedSongRefs(get(BlockedSongIdsKey).orEmpty()),
                BlockedSongRef(song.provider, song.id),
                blocked,
            ) ?: state.unavailable("content_menu_recommendation_unsupported")
            set(BlockedSongIdsKey, serializeBlockedSongRefs(next))
        }
    }

    override suspend fun saveCoverArtwork(song: ContentRef, artworkUrl: String): Boolean {
        val image = graph.artworkLoader.load(artworkUrl) ?: return false
        val file = chooseDesktopSaveFile(
            DesktopStrings["menu_cover_pick"], songExportFileName(selected.item.title, "png"),
        ) ?: return true
        writeDesktopContentPng(image, file.toPath())
        return true
    }

    override suspend fun refetchSong(song: ContentRef) {
        if (!song.supportsDesktopSongPin()) state.unavailable("content_menu_refetch_unsupported")
        val fresh = YouTube.queue(videoIds = listOf(song.id)).getOrThrow()
            .firstOrNull { it.id == song.id && !it.isPodcast }?.desktopContentItem()
            ?: error("Provider returned no matching song")
        graph.library.saveItems(listOf(fresh))
    }

    override fun openTrackInfo(song: ContentRef) {
        state.dismiss()
        openRoute(ArchiveRoutes.trackInfo(song.id))
    }
}

internal fun ContentRef.songDownloadId(): String = "$provider:$id"

internal fun ContentRef.supportsDesktopSongPin(): Boolean =
    provider == ContentProvider.YOUTUBE.id && kind == ContentKind.SONG && !id.startsWith("telegram://")

/** Idempotent desired-state updates; a full speed dial never silently drops an existing pin. */
internal fun desktopSongPins(pins: List<SpeedDialPin>, songId: String, pinned: Boolean): List<SpeedDialPin>? {
    val pin = SpeedDialPin(SpeedDialPinType.SONG, songId)
    if (!pinned) return pins.filterNot { it == pin }
    if (pin in pins) return pins
    if (pins.size >= 25) return null
    return pins + pin
}
