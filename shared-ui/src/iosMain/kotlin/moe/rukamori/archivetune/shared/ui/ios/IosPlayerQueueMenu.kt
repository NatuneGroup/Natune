package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.data.platform.ios.iosNowMillis
import moe.rukamori.archivetune.shared.ui.menu.*
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.*

/** iOS supplies observed state and service operations; the complete player menu is rendered in commonMain. */
@Composable
internal fun IosPlayerQueueMenu(
    menu: IosItemMenuState,
    graph: IosSessionGraph,
    host: IosHost,
    selected: ContentOccurrence,
    facts: SongMenuFacts,
    rows: List<OverflowAction>,
    tempo: IosTempoPitchActions,
    openRoute: (String) -> Unit,
) {
    val saved by graph.settings.snapshots.collectAsState()
    val preferences = saved ?: PreferenceSnapshot.EMPTY
    val playback by graph.player.snapshot.collectAsState()
    val pitch by tempo.playbackPitch.collectAsState()
    val sleep by graph.sleepTimer.state.collectAsState()
    val sourceActions = remember(graph, selected.occurrenceId) { IosSongSourceActions(graph, menu, selected) }
    val source by sourceActions.state.collectAsState()
    val item = selected.item
    // Telegram tracks always stream their TDLib file; the queue resolver never consults a chosen source for them.
    val sourceSelectable = item.ref.provider != moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef.SCHEME
    val track = PlayerQueueTrack(item.ref.id, item.title, item.artists.map { PlayerQueueArtist(it.id, it.name) },
        item.albumId?.let { PlayerQueueAlbum(it, item.album.orEmpty()) }, item.artworkUri, facts.isLocal)
    fun run(id: String) {
        val action = rows.firstOrNull { it.id == id && it.enabled } ?: return
        graph.scope.launch {
            try { action.run() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { menu.failed() }
        }
    }
    val actions = object : PlayerQueueMenuActions by tempo {
        override fun startRadio(track: PlayerQueueTrack) = run("start_radio")
        override fun playNext(track: PlayerQueueTrack) = run("play_next")
        override fun addToQueue(track: PlayerQueueTrack) = run("add_queue")
        override fun toggleSpeedDialPin(track: PlayerQueueTrack) = run("speed_dial")
        override fun downloadTrack(track: PlayerQueueTrack) = run("download")
        override fun removeDownload(track: PlayerQueueTrack) = run(if (facts.download == CollectionDownloadState.IN_PROGRESS) "downloading" else "remove_download")
        override fun showDetails(track: PlayerQueueTrack) { openRoute(ArchiveRoutes.mediaInfo(item.ref.id, item.ref.provider)) }
        override fun openArtist(artist: PlayerQueueArtist, collapse: PlayerQueueSheetCollapse) {
            artist.id?.let { openRoute(ArchiveRoutes.content(ContentRef(item.ref.provider, ContentKind.ARTIST, it))) }
        }
        override fun openAlbum(album: PlayerQueueAlbum) { openRoute(ArchiveRoutes.content(ContentRef(item.ref.provider, ContentKind.ALBUM, album.id))) }
        override fun copyLink(url: String) {
            graph.scope.launch { iosNowPlayingShareLink(item.ref, host)?.let(::copyToIosClipboard) }
        }
        override fun copyLinkAtCurrentPosition(url: String) {
            val position = graph.player.snapshot.value?.takeIf { it.occurrenceId == selected.occurrenceId }?.positionMs ?: 0L
            graph.scope.launch { iosNowPlayingShareLink(item.ref, host)?.let { link ->
                copyToIosClipboard(if (item.ref.provider == ContentProvider.YOUTUBE.id) "$link&t=${position.coerceAtLeast(0L) / 1_000L}" else link)
            } }
        }
        override fun shareLocalAudio(track: PlayerQueueTrack) {
            graph.scope.launch {
                try { iosMenuSharing(menu, host, graph).shareLocalAudio(item.ref.id, track.localMimeType) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { menu.failed() }
            }
        }
        override fun saveCanvas(track: PlayerQueueTrack) = run("save_canvas")
        override fun refreshSongSources(track: PlayerQueueTrack) = sourceActions.refreshSongSources(track)
        override suspend fun searchSongSources(query: String, sourceFilter: moe.rukamori.archivetune.shared.domain.source.AudioSourceType?, includeAmazon: Boolean) =
            sourceActions.searchSongSources(query, sourceFilter, includeAmazon)
        override fun chooseSongSource(track: PlayerQueueTrack, source: moe.rukamori.archivetune.shared.domain.source.AudioSourceType?) {
            if (sourceSelectable) sourceActions.chooseSongSource(track, source) else menu.failed()
        }
        override fun chooseSongSourceSearchResult(track: PlayerQueueTrack, request: PlayerSourceOverrideRequest) {
            if (sourceSelectable) sourceActions.chooseSongSourceSearchResult(track, request) else menu.failed()
        }
        override fun playYouTubeSearchResult(track: PlayerQueueTrack, result: PlayerSourceSearchResult) = sourceActions.playYouTubeSearchResult(track, result)
    }
    val state = PlayerQueueMenuState(
        style = preferences.selectedPlayerDesignStyle(), appleMusicExperience = preferences.appleMusicMenus(),
        download = when (facts.download) {
            CollectionDownloadState.COMPLETED -> PlayerQueueDownloadState.COMPLETED
            CollectionDownloadState.IN_PROGRESS -> PlayerQueueDownloadState.IN_PROGRESS
            else -> PlayerQueueDownloadState.NOT_DOWNLOADED
        },
        pinnedToSpeedDial = facts.speedDialPinned,
        externalDownloaderEnabled = preferences[ExternalDownloaderEnabledKey, false],
        externalDownloaderSupported = false,
        speedDialSupported = item.ref.iosYouTubeSong(),
        canvasFeatureEnabled = preferences[ArchiveTuneCanvasKey, false] || preferences[SpotifyCanvasKey, false],
        lowDataModeActive = preferences[NetworkMeteredKey, false],
        // Canvas save remains an owner-backed extra row; no artwork URL is mistaken for an observed canvas loop.
        hasCanvasArtwork = false, canvasRefetching = false,
        castAvailable = false, castConnected = false,
        artistSeparators = preferences[ArtistSeparatorsKey, ",;/&"],
        playlistLoad = PlayerQueuePlaylistLoadState(false, false, false, false),
        playbackSpeed = playback?.playbackSpeed ?: 1f, playbackPitch = pitch, sleepTimer = sleep,
        sourceChoices = source.sourceChoices, selectedSource = source.selectedSource,
        amazonSearchEnabled = source.amazonSearchEnabled, searchableSources = source.searchableSources,
        alwaysOnDisplaySupported = false,
        radioSupported = item.ref.iosYouTubeSong(),
        sourceSelectionSupported = sourceSelectable,
        downloadsSupported = facts.download != CollectionDownloadState.NOT_DOWNLOADED || iosDownloadRefusal(item.ref) == null,
        tempoPitchPresets = parseTempoPitchPresets(preferences[TempoPitchPresetsJsonKey]),
        tempoPitchRemembered = preferences[TempoPitchRememberedKey, false],
        menuStyle = preferences.playerMenuStyle(),
    )
    PlayerQueueMenuSheet(selected, track, state, actions, PlayerQueueClock(::iosNowMillis),
        artwork = { uri, modifier -> IosRemoteArtwork(uri, graph.artworkLoader, modifier) },
        extraActions = rows, library = graph.library, onDismiss = menu::dismiss, onFailure = menu::failed)
}
