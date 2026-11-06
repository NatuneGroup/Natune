/*
 * Ported from YumaPlayer's Spotify home, MuwMx/YumaPlayer at 6eb6438 (GPL-3.0),
 * ui/screens/spotifyhome/SpotifyHomeScreen.kt. Natune keeps its own view model, navigation events
 * and player queue; the page below them is the reference's. See docs/CREDITS.md.
 */

package moe.rukamori.archivetune.ui.screens.spotifyhome

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.extensions.togglePlayPause
import moe.rukamori.archivetune.models.toMediaMetadata
import moe.rukamori.archivetune.spotify.SPOTIFY_DJ_PLAYLIST_ID
import moe.rukamori.archivetune.spotify.SectionType
import moe.rukamori.archivetune.spotify.SpotifyHomeAction
import moe.rukamori.archivetune.spotify.SpotifyHomeNavigationEvent
import moe.rukamori.archivetune.spotify.SpotifyHomeScreenState
import moe.rukamori.archivetune.spotify.SpotifyHomeViewModel
import moe.rukamori.archivetune.spotify.isSpotifyDj
import moe.rukamori.archivetune.ui.component.ExpressivePullToRefreshBox
import moe.rukamori.archivetune.ui.screens.HomeSectionHeader
import moe.rukamori.archivetune.ui.screens.SpotifyHomeLayout
import moe.rukamori.archivetune.ui.screens.rememberSwitchToYouTube

/**
 * Opens a Spotify playlist tile, or hands the DJ off to Spotify.
 *
 * The DJ arrives shaped like a playlist and is not one — see [isSpotifyDj]. Navigating to it gave an
 * empty playlist page, which read as the tile being broken; Spotify's own app is the only place it
 * can actually play.
 */
@Composable
private fun rememberOpenSpotifyPlaylist(navController: NavController): (String) -> Unit {
    val context = LocalContext.current
    return remember(context, navController) {
        { playlistId: String ->
            if (isSpotifyDj(playlistId)) {
                Toast
                    .makeText(context, context.getString(R.string.spotify_dj_unsupported), Toast.LENGTH_LONG)
                    .show()
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://open.spotify.com/playlist/$SPOTIFY_DJ_PLAYLIST_ID"),
                        ),
                    )
                }
            } else {
                navController.navigate("spotify_playlist/$playlistId")
            }
            Unit
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun SpotifyHomeScreen(
    navController: NavController,
    headerScrollConnection: NestedScrollConnection? = null,
    viewModel: SpotifyHomeViewModel = hiltViewModel(),
) {
    val openSpotifyPlaylist = rememberOpenSpotifyPlaylist(navController)
    val playerConnection = LocalPlayerConnection.current ?: return
    val context = LocalContext.current
    val screenState by viewModel.screenState.collectAsStateWithLifecycle()
    val resolvingItemKey by viewModel.resolvingItemKey.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val onSwitchToYoutube = rememberSwitchToYouTube()

    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelSelection() }
    }

    LaunchedEffect(viewModel, navController, playerConnection, context) {
        viewModel.navigationEvents.collect { event ->
            when (event) {
                is SpotifyHomeNavigationEvent.OpenAlbum -> navController.navigate("album/${event.browseId}")
                is SpotifyHomeNavigationEvent.OpenArtist -> navController.navigate("artist/${event.id}")
                is SpotifyHomeNavigationEvent.PlayTracks -> playerConnection.playQueue(event.queue)
                is SpotifyHomeNavigationEvent.PlaySong ->
                    playerConnection.playQueue(
                        moe.rukamori.archivetune.playback.queues.YouTubeQueue.radio(
                            event.song.toMediaMetadata(),
                        ),
                    )
                is SpotifyHomeNavigationEvent.ShowMessage ->
                    Toast.makeText(context, event.messageResId, Toast.LENGTH_SHORT).show()
            }
        }
    }

    SpotifyHomeLayout(
        modifier =
            Modifier
                .fillMaxSize()
                .then(
                    if (headerScrollConnection != null) {
                        Modifier.nestedScroll(headerScrollConnection)
                    } else {
                        Modifier
                    },
                ),
    ) {
        when (val state = screenState) {
            SpotifyHomeScreenState.Loading -> {
                HomeStatePane(
                    iconResId = null,
                    messageResId = null,
                    showLoadingIndicator = true,
                )
            }
            SpotifyHomeScreenState.Empty -> {
                HomeStatePane(
                    iconResId = R.drawable.music_note,
                    messageResId = R.string.no_results_found,
                    actionResId = R.string.retry,
                    onAction = { viewModel.onAction(SpotifyHomeAction.Refresh) },
                )
            }
            is SpotifyHomeScreenState.Error -> {
                if (state.notAuthenticated == true) {
                    HomeStatePane(
                        iconResId = R.drawable.ic_about,
                        messageResId = R.string.spotify_not_connected,
                        actionResId = R.string.home_switch_to_yt,
                        onAction = onSwitchToYoutube,
                    )
                } else {
                    HomeStatePane(
                        iconResId = R.drawable.ic_about,
                        messageResId = state.messageResId,
                        actionResId = R.string.retry,
                        onAction = { viewModel.onAction(SpotifyHomeAction.Refresh) },
                    )
                }
            }
            is SpotifyHomeScreenState.Success -> {
                ExpressivePullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.onAction(SpotifyHomeAction.Refresh) },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        item(key = "spotify_recent_panel", contentType = "recent_panel") {
                            SpotifyRecentPanel(
                                recentItems = state.recentItems,
                                frequentArtists = state.frequentArtists,
                                onPlaylistClick = { playlist ->
                                    viewModel.cancelSelection()
                                    openSpotifyPlaylist(playlist.id)
                                },
                                onAlbumClick = { album ->
                                    viewModel.onAction(
                                        SpotifyHomeAction.AlbumClick(
                                            id = album.id,
                                            name = album.name,
                                            artist = album.artists.firstOrNull()?.name,
                                        ),
                                    )
                                },
                                onArtistClick = { artist ->
                                    viewModel.onAction(
                                        SpotifyHomeAction.ArtistClick(id = artist.id, name = artist.name),
                                    )
                                },
                                resolvingItemKey = resolvingItemKey,
                                modifier = Modifier.animateItem(),
                            )
                        }

                        state.sections.forEachIndexed { index, section ->
                            item(
                                key = "spotify_section_title_${section.title}_$index",
                                contentType = "section_header",
                            ) {
                                HomeSectionHeader(
                                    title = resolveSpotifySectionTitle(section),
                                    modifier = Modifier.animateItem(),
                                )
                            }

                            item(
                                key = "spotify_section_content_${section.title}_$index",
                                contentType = "section_content",
                            ) {
                                when (section.type) {
                                    SectionType.TRACKS -> {
                                        val sectionTitle = resolveSpotifySectionTitle(section)
                                        SpotifyTrackSectionRow(
                                            tracks = section.tracks,
                                            horizontalItemWidth = 240.dp,
                                            onTrackClick = { track ->
                                                if (mediaMetadata?.spotifyTrackId == track.id) {
                                                    viewModel.cancelSelection()
                                                    playerConnection.player.togglePlayPause()
                                                } else {
                                                    viewModel.onAction(
                                                        SpotifyHomeAction.TrackClick(track, section.tracks, sectionTitle),
                                                    )
                                                }
                                            },
                                            activeTrackId = mediaMetadata?.spotifyTrackId,
                                            isPlaying = isPlaying,
                                            resolvingItemKey = resolvingItemKey,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                    SectionType.ARTISTS -> {
                                        SpotifyArtistSectionRow(
                                            artists = section.artists,
                                            onArtistClick = { artist ->
                                                viewModel.onAction(
                                                    SpotifyHomeAction.ArtistClick(id = artist.id, name = artist.name),
                                                )
                                            },
                                            resolvingItemKey = resolvingItemKey,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                    SectionType.ALBUMS -> {
                                        SpotifyAlbumSectionRow(
                                            albums = section.albums,
                                            onAlbumClick = { album ->
                                                viewModel.onAction(
                                                    SpotifyHomeAction.AlbumClick(
                                                        id = album.id,
                                                        name = album.name,
                                                        artist = album.artists.firstOrNull()?.name,
                                                    ),
                                                )
                                            },
                                            resolvingItemKey = resolvingItemKey,
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                    SectionType.PLAYLISTS -> {
                                        SpotifyPlaylistSectionRow(
                                            playlists = section.playlists,
                                            onPlaylistClick = { playlist ->
                                                viewModel.cancelSelection()
                                                openSpotifyPlaylist(playlist.id)
                                            },
                                            modifier = Modifier.animateItem(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
