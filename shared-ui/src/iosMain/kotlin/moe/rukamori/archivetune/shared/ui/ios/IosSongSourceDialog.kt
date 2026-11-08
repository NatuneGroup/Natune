package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*
import moe.rukamori.archivetune.shared.ui.host.hostSourceSnapshot
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.*
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.painterResource
import platform.Foundation.NSUUID

@OptIn(ExperimentalForeignApi::class)
private class IosSongSourceActions(
    private val graph: IosSessionGraph,
    private val menu: IosItemMenuState,
    private val target: ContentOccurrence,
) : PlayerSongSourceActions {
    private fun projection(): SongSourcePickerState {
        val prefs = graph.settings.snapshots.value
        val sources = hostSourceSnapshot(prefs, iosSpotifyStreamingReady(prefs))
        return SongSourcePickerState(sources.order.filter { it in sources.enabled },
            SongSourceOverride.get(prefs?.get(SongSourceOverrideKey), target.item.ref.id),
            searchableSources = setOf(AudioSourceType.YOUTUBE))
    }
    val state = MutableStateFlow(projection())
    override fun refreshSongSources(track: PlayerQueueTrack) { state.value = projection() }
    override suspend fun searchSongSources(query: String, sourceFilter: AudioSourceType?, includeAmazon: Boolean): List<PlayerSourceSearchResult> {
        if (sourceFilter != null && sourceFilter != AudioSourceType.YOUTUBE) return emptyList()
        require(query.length in 2..512)
        val page = withContext(Dispatchers.Default) {
            graph.youtubeContent.page(ContentRequest(ContentProvider.YOUTUBE, ContentSurface.SEARCH, query, filter = ContentSearchFilter.SONGS), null)
        }
        return page.items.filter { it.item.ref.kind == ContentKind.SONG }.take(50).map { row ->
            val item = row.item
            PlayerSourceSearchResult(AudioSourceType.YOUTUBE, item.ref.id, item.title,
                item.artists.joinToString { it.name }.ifBlank { item.subtitle }, item.album, item.artworkUri,
                item.durationMs, PlayerSourceQuality.NONE, true)
        }
    }
    override fun chooseSongSource(track: PlayerQueueTrack, source: AudioSourceType?) {
        graph.scope.launch {
            try {
                graph.settings.edit { this[SongSourceOverrideKey] = SongSourceOverride.withOverride(this[SongSourceOverrideKey], track.mediaId, source) }
                refreshSongSources(track)
                if (graph.player.snapshot.value?.occurrenceId == target.occurrenceId) graph.retryAfterFailure(target.occurrenceId)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { menu.failed() }
        }
    }
    override fun chooseSongSourceSearchResult(track: PlayerQueueTrack, request: PlayerSourceOverrideRequest) {
        if (request is PlayerSourceOverrideRequest.Plain) chooseSongSource(track, request.source)
        else menu.unavailable()
    }
    override fun playYouTubeSearchResult(track: PlayerQueueTrack, result: PlayerSourceSearchResult) {
        graph.scope.launch {
            try {
                val item = ContentItem(ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, result.trackId), result.title,
                    subtitle = result.artist, artworkUri = result.thumbnailUrl, durationMs = result.durationMs)
                check(graph.queue.startRadio(item.ref) {
                    ContentOccurrence("ios-source-radio:" + NSUUID().UUIDString, item)
                })
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { menu.failed() }
        }
    }
}

@Composable
internal fun IosSongSourceDialog(graph: IosSessionGraph, menu: IosItemMenuState, target: ContentOccurrence) {
    val item = target.item
    val track = remember(target) { PlayerQueueTrack(item.ref.id, item.title,
        item.artists.map { PlayerQueueArtist(it.id, it.name) }, item.albumId?.let { PlayerQueueAlbum(it, item.album.orEmpty()) },
        item.artworkUri, false) }
    val actions = remember(graph, target) { IosSongSourceActions(graph, menu, target) }
    val state by actions.state.collectAsState()
    LaunchedEffect(actions) { graph.settings.snapshots.collect { actions.refreshSongSources(track) } }
    PlayerQueueSongSourceDialog(track, state, actions,
        icon = { mark, tint, modifier ->
            val resource = when (mark) {
                PlayerQueueMenuIcon.SEARCH -> Res.drawable.m4c_icon_search
                PlayerQueueMenuIcon.CLOSE -> Res.drawable.m12_icon_player_close
                PlayerQueueMenuIcon.DONE -> Res.drawable.m4hv_icon_done
                PlayerQueueMenuIcon.TUNE -> Res.drawable.m4hv_icon_discover_tune
                else -> Res.drawable.m6_icon_account
            }
            Icon(painterResource(resource), null, modifier, tint)
        }, artwork = { uri, modifier -> IosRemoteArtwork(uri, graph.artworkLoader, modifier) },
        onDismiss = { menu.sourceTarget = null })
}
