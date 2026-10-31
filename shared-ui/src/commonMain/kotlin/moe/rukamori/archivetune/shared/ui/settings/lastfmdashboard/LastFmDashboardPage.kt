@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopAlbum
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTopArtist
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmUserProfile
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.navigation.LocalBackToMain
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/**
 * Draws one image for the host. The host owns the loader and the crop, so the shared UI does not depend on an image
 * library. [circle] asks for a circular crop.
 */
typealias LastFmDashboardImage = @Composable (url: String, modifier: Modifier, circle: Boolean) -> Unit

/**
 * The Last.fm dashboard. [binding] owns the data and every action. The host supplies the picture slot, the
 * YouTube-thumbnail preference, and the navigation callbacks. The page reads no app preferences.
 */
@Composable
fun LastFmDashboardPage(
    binding: LastFmDashboardBinding,
    image: LastFmDashboardImage,
    preferYouTubeArtwork: Boolean,
    back: () -> Unit,
    onSignIn: () -> Unit,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    modifier: Modifier = Modifier,
) {
    val state by binding.state.collectAsState()
    val colors = MaterialTheme.colorScheme
    val palette = remember(colors) { dashboardPalette(colors) }
    val snackbarHostState = remember { SnackbarHostState() }
    val songCopied = stringResource(Res.string.m4lf_song_copied)
    val noMatch = stringResource(Res.string.m4lf_no_yt_match)
    val playerUnavailable = stringResource(Res.string.m4lf_player_unavailable)

    LaunchedEffect(binding) { binding.refresh() }
    LaunchedEffect(binding, songCopied, noMatch, playerUnavailable) {
        binding.messages.collect { message ->
            snackbarHostState.showSnackbar(
                when (message) {
                    LastFmDashboardMessage.SONG_COPIED -> songCopied
                    LastFmDashboardMessage.NO_YOUTUBE_MATCH -> noMatch
                    LastFmDashboardMessage.PLAYER_UNAVAILABLE -> playerUnavailable
                },
            )
        }
    }

    val avatarUrl = state.snapshot?.profile?.images?.let { LastFmArtwork.best(it) }
    Box(modifier = modifier.fillMaxSize().background(palette.pageBackground)) {
        Column(modifier = Modifier.fillMaxSize()) {
            DashboardHeader(
                binding = binding,
                state = state,
                palette = palette,
                avatarUrl = avatarUrl,
                image = image,
                back = back,
            )
            when {
                !state.checked -> CenteredSpinner(palette = palette, modifier = Modifier.weight(1f))
                !state.signedIn -> NotSignedIn(
                    palette = palette,
                    onSignIn = onSignIn,
                    modifier = Modifier.weight(1f),
                )
                else -> SignedInContent(
                    binding = binding,
                    state = state,
                    palette = palette,
                    image = image,
                    preferYouTubeArtwork = preferYouTubeArtwork,
                    contentPadding = contentPadding,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val sheetTrack = state.sheetTrack
        if (sheetTrack != null) {
            TrackSheet(
                binding = binding,
                track = sheetTrack,
                genre = state.sheetGenre,
                artwork = state.sheetArtwork,
                loading = state.loadingAction,
                palette = palette,
                image = image,
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = contentPadding.calculateBottomPadding() + 8.dp),
        )
    }
}

/** Colour tokens for the dashboard. They come from the host's colour scheme, and the dark flag follows its background. */
private data class DashboardPalette(
    val pageBackground: Color,
    val cardBackground: Color,
    val accent: Color,
    val statsHeroInner: Color,
    val statsHeroNumberText: Color,
    val statsHeroLabelText: Color,
    val statsPillBackground: Color,
    val statsPillValueText: Color,
    val statsPillLabelText: Color,
    val heroArrowCircleBackground: Color,
    val heroArrowIconTint: Color,
    val rankingBadgeBackground: Color,
    val rankingBadgeText: Color,
    val playCountPillBackground: Color,
    val playCountPillText: Color,
    val nowPlayingRowBackground: Color,
    val nowPlayingPillBackground: Color,
    val nowPlayingPillText: Color,
    val nowPlayingDotColor: Color,
    val nowPlayingTrackTitle: Color,
    val nowPlayingTrackArtist: Color,
    val artworkPlaceholderBackground: Color,
    val artworkPlaceholderTint: Color,
    val filterPillBackground: Color,
    val filterPillText: Color,
    val filterPillIconTint: Color,
    val dropdownBackground: Color,
    val dropdownActiveItemBackground: Color,
    val dropdownActiveItemText: Color,
    val dropdownActiveItemIconTint: Color,
    val dropdownInactiveItemText: Color,
    val dropdownInactiveItemIconTint: Color,
    val dropdownCheckTint: Color,
    val overflowIconTint: Color,
    val topAppBarContainer: Color,
    val topAppBarIconTint: Color,
    val topAppBarTitleText: Color,
    val fallbackAvatarBackground: Color,
    val fallbackAvatarTint: Color,
    val signInAvatarBackground: Color,
    val signInAvatarTint: Color,
    val signInButtonText: Color,
    val signInButtonContainer: Color,
    val emptyHintText: Color,
    val dividerColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
)

private fun dashboardPalette(cs: ColorScheme): DashboardPalette {
    val isDark = cs.background.luminance() < 0.5f
    val accent = cs.primary
    val onAccent = cs.onPrimary
    val elevated = cs.surfaceContainerHigh
    return DashboardPalette(
        pageBackground = cs.background,
        cardBackground = cs.surfaceContainer,
        accent = accent,
        statsHeroInner = cs.primaryContainer,
        statsHeroNumberText = cs.onPrimaryContainer,
        statsHeroLabelText = cs.onPrimaryContainer.copy(alpha = 0.75f),
        statsPillBackground = elevated,
        statsPillValueText = cs.onSurface,
        statsPillLabelText = cs.onSurfaceVariant,
        heroArrowCircleBackground = accent,
        heroArrowIconTint = onAccent,
        rankingBadgeBackground = elevated,
        rankingBadgeText = accent,
        playCountPillBackground = elevated,
        playCountPillText = cs.onSurfaceVariant,
        nowPlayingRowBackground = accent.copy(alpha = if (isDark) 0.16f else 0.12f),
        nowPlayingPillBackground = accent,
        nowPlayingPillText = onAccent,
        nowPlayingDotColor = onAccent,
        nowPlayingTrackTitle = cs.onSurface,
        nowPlayingTrackArtist = cs.onSurface.copy(alpha = 0.8f),
        artworkPlaceholderBackground = elevated,
        artworkPlaceholderTint = cs.onSurfaceVariant,
        filterPillBackground = elevated,
        filterPillText = cs.onSurface,
        filterPillIconTint = cs.onSurfaceVariant,
        dropdownBackground = cs.surfaceContainer,
        dropdownActiveItemBackground = accent.copy(alpha = if (isDark) 0.22f else 0.14f),
        dropdownActiveItemText = accent,
        dropdownActiveItemIconTint = accent,
        dropdownInactiveItemText = cs.onSurface,
        dropdownInactiveItemIconTint = cs.onSurfaceVariant,
        dropdownCheckTint = accent,
        overflowIconTint = cs.onSurface,
        topAppBarContainer = cs.background,
        topAppBarIconTint = cs.onSurface,
        topAppBarTitleText = cs.onSurface,
        fallbackAvatarBackground = cs.secondaryContainer,
        fallbackAvatarTint = cs.onSecondaryContainer,
        signInAvatarBackground = cs.secondaryContainer,
        signInAvatarTint = cs.onSecondaryContainer,
        signInButtonText = onAccent,
        signInButtonContainer = accent,
        emptyHintText = cs.onSurfaceVariant,
        dividerColor = cs.onSurface.copy(alpha = 0.08f),
        textPrimary = cs.onBackground,
        textSecondary = cs.onSurfaceVariant,
    )
}

/** The count placeholder for a total that Last.fm did not report, so a guessed number never shows. */
private const val UNKNOWN_COUNT = "\u2014"

private fun countText(total: Int?): String = total?.let { groupedCount(it.toLong()) } ?: UNKNOWN_COUNT

private fun LastFmDashboardFilter.labelRes(): StringResource = when (this) {
    LastFmDashboardFilter.RECENT -> Res.string.m4lf_filter_recent
    LastFmDashboardFilter.TOP_TRACKS -> Res.string.m4lf_filter_top_tracks
    LastFmDashboardFilter.TOP_ARTISTS -> Res.string.m4lf_filter_top_artists
    LastFmDashboardFilter.TOP_ALBUMS -> Res.string.m4lf_filter_top_albums
}

private fun LastFmDashboardFilter.iconRes(): DrawableResource = when (this) {
    LastFmDashboardFilter.RECENT -> Res.drawable.m4_icon_cached
    LastFmDashboardFilter.TOP_TRACKS -> Res.drawable.m4_icon_music_note
    LastFmDashboardFilter.TOP_ARTISTS -> Res.drawable.m4lf_icon_users
    LastFmDashboardFilter.TOP_ALBUMS -> Res.drawable.m4_icon_album
}

/**
 * Back, search and the profile avatar. The back control matches the management screens: a text action that returns
 * on tap and jumps to the main tab root on long press.
 */
@Composable
private fun DashboardHeader(
    binding: LastFmDashboardBinding,
    state: LastFmDashboardUiState,
    palette: DashboardPalette,
    avatarUrl: String?,
    image: LastFmDashboardImage,
    back: () -> Unit,
) {
    val backToMain = LocalBackToMain.current
    val motion = LocalOpticalPolicy.current.motion
    val headerEnter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
        slideInVertically(initialOffsetY = { it / 8 })
    val headerExit = fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) +
        slideOutVertically(targetOffsetY = { it / 8 })
    Surface(color = palette.topAppBarContainer, tonalElevation = 0.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .combinedClickable(role = Role.Button, onClick = back, onLongClick = backToMain)
                    .defaultMinSize(minHeight = 40.dp)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(Res.string.m4_back),
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.accent,
                )
            }
            // Search and the avatar need an account, so a signed-out page keeps only the title row.
            AnimatedContent(
                targetState = state.searchVisible && state.signedIn,
                label = "lastfm_header_search_swap",
                transitionSpec = {
                    if (motion) {
                        (headerEnter togetherWith headerExit)
                    } else {
                        (EnterTransition.None togetherWith ExitTransition.None)
                    }
                },
            ) { searching ->
                if (searching) {
                    SearchField(binding = binding, state = state, palette = palette)
                } else {
                    TitleRow(
                        binding = binding,
                        palette = palette,
                        avatarUrl = avatarUrl,
                        image = image,
                        signedIn = state.signedIn,
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleRow(
    binding: LastFmDashboardBinding,
    palette: DashboardPalette,
    avatarUrl: String?,
    image: LastFmDashboardImage,
    signedIn: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.m4lf_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = palette.topAppBarTitleText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (signedIn) {
            IconButton(onClick = { binding.setSearchVisible(true) }) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_search),
                    contentDescription = stringResource(Res.string.m4_search),
                    tint = palette.topAppBarIconTint,
                )
            }
            Spacer(Modifier.width(4.dp))
            ProfileAvatar(url = avatarUrl, image = image, palette = palette)
        }
    }
}

@Composable
private fun SearchField(
    binding: LastFmDashboardBinding,
    state: LastFmDashboardUiState,
    palette: DashboardPalette,
) {
    OutlinedTextField(
        value = state.searchQuery,
        onValueChange = { binding.setSearchQuery(it) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        singleLine = true,
        placeholder = { Text(stringResource(Res.string.m4lf_search_placeholder)) },
        leadingIcon = {
            IconButton(onClick = { binding.setSearchVisible(false) }) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_arrow_back),
                    contentDescription = stringResource(Res.string.m4_back),
                    tint = palette.topAppBarIconTint,
                )
            }
        },
        trailingIcon = {
            if (state.searchQuery.isNotEmpty()) {
                IconButton(onClick = { binding.setSearchQuery("") }) {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_close),
                        contentDescription = stringResource(Res.string.m4lf_clear_search),
                        tint = palette.topAppBarIconTint,
                    )
                }
            }
        },
    )
}

@Composable
private fun ProfileAvatar(url: String?, image: LastFmDashboardImage, palette: DashboardPalette) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(palette.fallbackAvatarBackground),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            image(url, Modifier.fillMaxSize(), true)
        } else {
            Icon(
                painter = painterResource(Res.drawable.m4hv_icon_person),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = palette.fallbackAvatarTint,
            )
        }
    }
}

@Composable
private fun NotSignedIn(palette: DashboardPalette, onSignIn: () -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = palette.signInAvatarBackground,
            modifier = Modifier.size(72.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(Res.drawable.m4_icon_music_note),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = palette.signInAvatarTint,
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(Res.string.m4lf_sign_in_required),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.m4lf_sign_in_required_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onSignIn,
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(
                containerColor = palette.signInButtonContainer,
                contentColor = palette.signInButtonText,
            ),
        ) {
            Text(
                text = stringResource(Res.string.m4lf_sign_in_button),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SignedInContent(
    binding: LastFmDashboardBinding,
    state: LastFmDashboardUiState,
    palette: DashboardPalette,
    image: LastFmDashboardImage,
    preferYouTubeArtwork: Boolean,
    contentPadding: PaddingValues,
    modifier: Modifier,
) {
    val snapshot = state.snapshot
    val recentPage = snapshot?.recentTracks
    val topTracksPage = snapshot?.topTracks
    val topArtistsPage = snapshot?.topArtists
    val topAlbumsPage = snapshot?.topAlbums
    // Search only applies while the field is open and holds text.
    val query = state.searchQuery.trim().takeIf { state.searchVisible && it.isNotEmpty() }
    val recentArtwork = remember(recentPage) { recentArtworkByTrack(recentPage?.items.orEmpty()) }
    // Recent repeats merge before the search runs, so a count stays with its scrobble.
    val recentVisible = remember(recentPage, query) {
        recentPage?.items?.let { items ->
            recentRows(items).filter { row -> query == null || matchesSearch(query, row.track.name, row.track.artist) }
        }
    }
    val topTracksVisible = remember(topTracksPage, query) {
        topTracksPage?.items?.filter { track -> query == null || matchesSearch(query, track.name, track.artist) }
    }
    val topArtistsVisible = remember(topArtistsPage, query) {
        topArtistsPage?.items?.filter { artist -> query == null || matchesSearch(query, artist.name) }
    }
    val topAlbumsVisible = remember(topAlbumsPage, query) {
        topAlbumsPage?.items?.filter { album -> query == null || matchesSearch(query, album.name, album.artist) }
    }
    val noRecent = stringResource(Res.string.m4lf_no_recent_tracks)
    val noTopTracks = stringResource(Res.string.m4lf_no_top_tracks)
    val noTopArtists = stringResource(Res.string.m4lf_no_top_artists)
    val noTopAlbums = stringResource(Res.string.m4lf_no_top_albums)
    val noSearch = stringResource(Res.string.m4lf_no_search_results)
    val listBottom = contentPadding.calculateBottomPadding() + 16.dp

    Column(modifier = modifier) {
        if (!state.searchVisible) {
            HeroStatsCard(
                profile = snapshot?.profile,
                refreshing = state.refreshing,
                topTracksTotal = topTracksPage?.total,
                topArtistsTotal = topArtistsPage?.total,
                topAlbumsTotal = topAlbumsPage?.total,
                palette = palette,
                onOpenProfile = { binding.openProfile(snapshot?.profile?.profileUrl) },
            )
            Spacer(Modifier.height(8.dp))
        }
        Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp)) {
            FilterHeader(
                filter = state.filter,
                palette = palette,
                onSelect = { binding.selectFilter(it) },
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = listBottom),
            ) {
                when (state.filter) {
                    LastFmDashboardFilter.RECENT -> {
                        val rows = recentVisible
                        if (rows == null) {
                            item(key = "recent_status") {
                                SectionStatus(
                                    loaded = state.loaded,
                                    refreshing = state.refreshing,
                                    palette = palette,
                                    onRetry = { binding.refresh() },
                                )
                            }
                        } else if (rows.isEmpty()) {
                            item(key = "recent_empty") {
                                val searching = query != null && recentPage?.items?.isNotEmpty() == true
                                EmptyHint(text = if (searching) noSearch else noRecent, palette = palette)
                            }
                        } else {
                            itemsIndexed(
                                rows,
                                key = { index, row -> "recent_${index}_${row.track.playedAtEpochSeconds}" },
                            ) { _, row ->
                                DashboardTrackRow(
                                    binding = binding,
                                    palette = palette,
                                    image = image,
                                    track = row.track.toDashboardTrack(row.playCount),
                                    nowPlaying = row.nowPlaying,
                                    rank = null,
                                    fallbackArtwork = recentArtwork[trackKey(row.track.name, row.track.artist)],
                                    preferYouTubeArtwork = preferYouTubeArtwork,
                                )
                            }
                        }
                    }
                    LastFmDashboardFilter.TOP_TRACKS -> {
                        val tracks = topTracksVisible
                        if (tracks == null) {
                            item(key = "top_tracks_status") {
                                SectionStatus(
                                    loaded = state.loaded,
                                    refreshing = state.refreshing,
                                    palette = palette,
                                    onRetry = { binding.refresh() },
                                )
                            }
                        } else if (tracks.isEmpty()) {
                            item(key = "top_tracks_empty") {
                                val searching = query != null && topTracksPage?.items?.isNotEmpty() == true
                                EmptyHint(text = if (searching) noSearch else noTopTracks, palette = palette)
                            }
                        } else {
                            itemsIndexed(
                                tracks,
                                key = { index, track -> "top_${index}_${track.name.orEmpty()}" },
                            ) { index, track ->
                                DashboardTrackRow(
                                    binding = binding,
                                    palette = palette,
                                    image = image,
                                    track = track.toDashboardTrack(),
                                    nowPlaying = false,
                                    rank = track.rank ?: (index + 1),
                                    fallbackArtwork = recentArtwork[trackKey(track.name, track.artist)],
                                    preferYouTubeArtwork = preferYouTubeArtwork,
                                )
                            }
                        }
                    }
                    LastFmDashboardFilter.TOP_ARTISTS -> {
                        val artists = topArtistsVisible
                        if (artists == null) {
                            item(key = "top_artists_status") {
                                SectionStatus(
                                    loaded = state.loaded,
                                    refreshing = state.refreshing,
                                    palette = palette,
                                    onRetry = { binding.refresh() },
                                )
                            }
                        } else if (artists.isEmpty()) {
                            item(key = "top_artists_empty") {
                                val searching = query != null && topArtistsPage?.items?.isNotEmpty() == true
                                EmptyHint(text = if (searching) noSearch else noTopArtists, palette = palette)
                            }
                        } else {
                            itemsIndexed(
                                artists,
                                key = { index, artist -> "artist_${index}_${artist.name.orEmpty()}" },
                            ) { index, artist ->
                                DashboardArtistRow(
                                    binding = binding,
                                    palette = palette,
                                    image = image,
                                    artist = artist,
                                    rank = artist.rank ?: (index + 1),
                                )
                            }
                        }
                    }
                    LastFmDashboardFilter.TOP_ALBUMS -> {
                        val albums = topAlbumsVisible
                        if (albums == null) {
                            item(key = "top_albums_status") {
                                SectionStatus(
                                    loaded = state.loaded,
                                    refreshing = state.refreshing,
                                    palette = palette,
                                    onRetry = { binding.refresh() },
                                )
                            }
                        } else if (albums.isEmpty()) {
                            item(key = "top_albums_empty") {
                                val searching = query != null && topAlbumsPage?.items?.isNotEmpty() == true
                                EmptyHint(text = if (searching) noSearch else noTopAlbums, palette = palette)
                            }
                        } else {
                            itemsIndexed(
                                albums,
                                key = { index, album -> "album_${index}_${album.name.orEmpty()}" },
                            ) { index, album ->
                                DashboardAlbumRow(
                                    binding = binding,
                                    palette = palette,
                                    image = image,
                                    album = album,
                                    rank = album.rank ?: (index + 1),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroStatsCard(
    profile: LastFmUserProfile?,
    refreshing: Boolean,
    topTracksTotal: Int?,
    topArtistsTotal: Int?,
    topAlbumsTotal: Int?,
    palette: DashboardPalette,
    onOpenProfile: () -> Unit,
) {
    if (profile == null) {
        if (refreshing) CenteredSpinner(palette = palette)
        return
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = palette.cardBackground,
            tonalElevation = 2.dp,
            shadowElevation = 4.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = palette.statsHeroInner,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Column(
                            modifier = Modifier.align(Alignment.Center),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                text = profile.playCount?.let { groupedCount(it) } ?: UNKNOWN_COUNT,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                color = palette.statsHeroNumberText,
                            )
                            Text(
                                text = stringResource(Res.string.m4lf_scrobbles),
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.statsHeroLabelText,
                            )
                        }
                        Surface(
                            onClick = onOpenProfile,
                            shape = CircleShape,
                            color = palette.heroArrowCircleBackground,
                            modifier = Modifier.align(Alignment.CenterEnd).size(46.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    painter = painterResource(Res.drawable.m4hv_icon_navigate_next),
                                    contentDescription = stringResource(Res.string.m4lf_open_in_lastfm),
                                    tint = palette.heroArrowIconTint,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    StatPill(
                        label = stringResource(Res.string.m4lf_filter_top_tracks),
                        value = countText(topTracksTotal),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                    StatPill(
                        label = stringResource(Res.string.m4lf_filter_top_artists),
                        value = countText(topArtistsTotal),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                    StatPill(
                        label = stringResource(Res.string.m4lf_filter_top_albums),
                        value = countText(topAlbumsTotal),
                        palette = palette,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun StatPill(label: String, value: String, palette: DashboardPalette, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = palette.statsPillBackground,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = palette.statsPillValueText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = palette.statsPillLabelText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun FilterHeader(
    filter: LastFmDashboardFilter,
    palette: DashboardPalette,
    onSelect: (LastFmDashboardFilter) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(Res.string.m4lf_list),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = palette.textPrimary,
        )
        Box {
            Surface(
                onClick = { menuOpen = true },
                shape = RoundedCornerShape(50),
                color = palette.filterPillBackground,
                tonalElevation = 1.dp,
                modifier = Modifier.heightIn(min = 34.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(filter.iconRes()),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = palette.filterPillIconTint,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(filter.labelRes()),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.filterPillText,
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        painter = painterResource(Res.drawable.m4lf_icon_expand_more),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = palette.filterPillIconTint,
                    )
                }
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.padding(vertical = 4.dp),
                shape = RoundedCornerShape(24.dp),
                containerColor = palette.dropdownBackground,
                tonalElevation = 3.dp,
                shadowElevation = 3.dp,
            ) {
                LastFmDashboardFilter.entries.forEach { option ->
                    FilterOption(
                        option = option,
                        active = option == filter,
                        palette = palette,
                        onClick = {
                            menuOpen = false
                            onSelect(option)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterOption(
    option: LastFmDashboardFilter,
    active: Boolean,
    palette: DashboardPalette,
    onClick: () -> Unit,
) {
    val check: (@Composable () -> Unit)? = if (active) {
        {
            Icon(
                painter = painterResource(Res.drawable.m4lf_icon_check),
                contentDescription = null,
                tint = palette.dropdownCheckTint,
            )
        }
    } else {
        null
    }
    DropdownMenuItem(
        text = {
            Text(
                text = stringResource(option.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) palette.dropdownActiveItemText else palette.dropdownInactiveItemText,
            )
        },
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) palette.dropdownActiveItemBackground else Color.Transparent),
        leadingIcon = {
            Icon(
                painter = painterResource(option.iconRes()),
                contentDescription = null,
                tint = if (active) palette.dropdownActiveItemIconTint else palette.dropdownInactiveItemIconTint,
            )
        },
        trailingIcon = check,
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
    )
}

@Composable
private fun DashboardTrackRow(
    binding: LastFmDashboardBinding,
    palette: DashboardPalette,
    image: LastFmDashboardImage,
    track: LastFmDashboardTrack,
    nowPlaying: Boolean,
    rank: Int?,
    fallbackArtwork: String?,
    preferYouTubeArtwork: Boolean,
) {
    // With "prefer YouTube thumbnails" on, Last.fm art is skipped and the host resolves the row first, as the
    // baseline did. Otherwise the row resolves only when Last.fm and the recent-track fallback both have nothing.
    val initialArtwork = if (preferYouTubeArtwork) null else (track.lastFmArtwork ?: fallbackArtwork)
    // produceState keeps its last value across key changes, so each restart begins again from this item's own image.
    val artwork by produceState<String?>(initialArtwork, track.artworkKey, preferYouTubeArtwork, initialArtwork) {
        value = initialArtwork
        if (value.isNullOrBlank()) {
            value = binding.resolveTrackArtwork(track, preferYouTubeArtwork)
        }
    }
    val artworkUrl = artwork
    val plays = track.playCount
    Surface(
        shape = if (nowPlaying) RoundedCornerShape(22.dp) else RoundedCornerShape(18.dp),
        color = if (nowPlaying) palette.nowPlayingRowBackground else Color.Transparent,
        tonalElevation = if (nowPlaying) 1.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.artworkPlaceholderBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    image(artworkUrl, Modifier.fillMaxSize(), false)
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_music_note),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = palette.artworkPlaceholderTint,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            if (rank != null) {
                RankBadge(rank = rank, palette = palette)
                Spacer(Modifier.width(8.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = if (nowPlaying) palette.nowPlayingTrackTitle else palette.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = track.artist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (nowPlaying) palette.nowPlayingTrackArtist else palette.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (nowPlaying) {
                NowPlayingPill(palette = palette)
            } else if (plays != null && plays > 1) {
                PlayCountPill(text = stringResource(Res.string.m4lf_repeat_count, plays), palette = palette)
            }
            IconButton(onClick = { binding.openTrackSheet(track) }) {
                Icon(
                    painter = painterResource(Res.drawable.m4hv_icon_more_vert),
                    contentDescription = stringResource(Res.string.m4_more),
                    tint = palette.overflowIconTint,
                )
            }
        }
    }
}

/** Artists have no track actions, so the row only shows the artist, the rank and the count. */
@Composable
private fun DashboardArtistRow(
    binding: LastFmDashboardBinding,
    palette: DashboardPalette,
    image: LastFmDashboardImage,
    artist: LastFmTopArtist,
    rank: Int,
) {
    val name = artist.name.orEmpty()
    val initialArtwork = LastFmArtwork.best(artist.images)
    val artwork by produceState<String?>(initialArtwork, name, initialArtwork) {
        value = initialArtwork
        if (value.isNullOrBlank() && name.isNotBlank()) {
            value = binding.resolveArtistArtwork(name)
        }
    }
    val artworkUrl = artwork
    val plays = artist.playCount
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(palette.artworkPlaceholderBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    image(artworkUrl, Modifier.fillMaxSize(), true)
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.m4hv_icon_person),
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = palette.artworkPlaceholderTint,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            RankBadge(rank = rank, palette = palette)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = plays?.let { stringResource(Res.string.m4lf_playcount, it) }
                        ?: stringResource(Res.string.m4lf_filter_top_artists),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (plays != null && plays > 0) {
                PlayCountPill(text = stringResource(Res.string.m4lf_repeat_count, plays), palette = palette)
            }
        }
    }
}

/** Albums carry no track actions either, so the row shows the album, its artist, the rank and the count. */
@Composable
private fun DashboardAlbumRow(
    binding: LastFmDashboardBinding,
    palette: DashboardPalette,
    image: LastFmDashboardImage,
    album: LastFmTopAlbum,
    rank: Int,
) {
    val title = album.name.orEmpty()
    val albumArtist = album.artist
    val initialArtwork = LastFmArtwork.best(album.images)
    val artwork by produceState<String?>(initialArtwork, title, albumArtist, initialArtwork) {
        value = initialArtwork
        if (value.isNullOrBlank() && title.isNotBlank()) {
            value = binding.resolveAlbumArtwork(title, albumArtist)
        }
    }
    val artworkUrl = artwork
    val plays = album.playCount
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .padding(vertical = 6.dp, horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(palette.artworkPlaceholderBackground),
                contentAlignment = Alignment.Center,
            ) {
                if (!artworkUrl.isNullOrBlank()) {
                    image(artworkUrl, Modifier.fillMaxSize(), false)
                } else {
                    Icon(
                        painter = painterResource(Res.drawable.m4_icon_album),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = palette.artworkPlaceholderTint,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            RankBadge(rank = rank, palette = palette)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.textPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = albumArtist.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = palette.textSecondary,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (plays != null && plays > 0) {
                PlayCountPill(text = stringResource(Res.string.m4lf_repeat_count, plays), palette = palette)
            }
        }
    }
}

@Composable
private fun RankBadge(rank: Int, palette: DashboardPalette) {
    Surface(
        modifier = Modifier.size(28.dp),
        shape = CircleShape,
        color = palette.rankingBadgeBackground,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = rank.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = palette.rankingBadgeText,
            )
        }
    }
}

@Composable
private fun PlayCountPill(text: String, palette: DashboardPalette) {
    Surface(
        shape = RoundedCornerShape(50),
        color = palette.playCountPillBackground,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = palette.playCountPillText,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** The pulse follows motion settings: when motion is reduced the pill stays still. */
@Composable
private fun NowPlayingPill(palette: DashboardPalette) {
    val motion = LocalOpticalPolicy.current.motion
    val pulse: State<Float>? = if (motion) {
        rememberInfiniteTransition(label = "lastfm_now_playing_pulse").animateFloat(
            initialValue = 1f,
            targetValue = 1.06f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "lastfm_pulse_scale",
        )
    } else {
        null
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = palette.nowPlayingPillBackground,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
        modifier = Modifier.graphicsLayer {
            val scale = pulse?.value ?: 1f
            scaleX = scale
            scaleY = scale
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(palette.nowPlayingDotColor),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(Res.string.m4_now_playing),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = palette.nowPlayingPillText,
            )
        }
    }
}

@Composable
private fun TrackSheet(
    binding: LastFmDashboardBinding,
    track: LastFmDashboardTrack,
    genre: String?,
    artwork: String?,
    loading: LastFmTrackAction?,
    palette: DashboardPalette,
    image: LastFmDashboardImage,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { binding.closeTrackSheet() },
        sheetState = sheetState,
        containerColor = palette.cardBackground,
        contentColor = palette.textPrimary,
    ) {
        Surface(
            onClick = { binding.runTrackAction(LastFmTrackAction.START_MIX) },
            shape = RoundedCornerShape(20.dp),
            color = palette.statsHeroInner,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    shape = CircleShape,
                    color = palette.accent,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (!artwork.isNullOrBlank()) {
                            image(artwork, Modifier.fillMaxSize(), true)
                        }
                        if (loading == LastFmTrackAction.START_MIX) {
                            DashboardSpinner(modifier = Modifier.size(24.dp), color = palette.nowPlayingPillText)
                        } else if (artwork.isNullOrBlank()) {
                            Icon(
                                painter = painterResource(Res.drawable.m4hv_icon_navigate_next),
                                contentDescription = null,
                                tint = palette.nowPlayingPillText,
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(Res.string.m4lf_start_mix),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = palette.statsHeroNumberText,
                    )
                    Text(
                        text = listOfNotNull(track.artist?.takeIf { it.isNotBlank() }, track.title)
                            .joinToString(" - "),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.statsHeroLabelText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        ListItem(
            headlineContent = {
                Text(text = stringResource(Res.string.m4lf_genre), color = palette.textPrimary)
            },
            supportingContent = {
                Text(
                    text = genre ?: stringResource(Res.string.m4lf_unknown_genre),
                    color = palette.textSecondary,
                )
            },
            leadingContent = {
                Icon(
                    painter = painterResource(Res.drawable.m4lf_icon_tag),
                    contentDescription = null,
                    tint = palette.textSecondary,
                )
            },
            modifier = Modifier.clickable { binding.openGenres() },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        HorizontalDivider(color = palette.dividerColor)
        OverflowActionItem(
            label = stringResource(Res.string.m4lf_play_in_natune),
            icon = Res.drawable.m4lf_icon_play,
            enabled = loading == null,
            loading = loading == LastFmTrackAction.PLAY,
            palette = palette,
            onClick = { binding.runTrackAction(LastFmTrackAction.PLAY) },
        )
        OverflowActionItem(
            label = stringResource(Res.string.m4lf_play_next),
            icon = Res.drawable.m4lf_icon_skip_next,
            enabled = loading == null,
            loading = loading == LastFmTrackAction.PLAY_NEXT,
            palette = palette,
            onClick = { binding.runTrackAction(LastFmTrackAction.PLAY_NEXT) },
        )
        OverflowActionItem(
            label = stringResource(Res.string.m4lf_add_to_queue),
            icon = Res.drawable.m4lf_icon_add_to_queue,
            enabled = loading == null,
            loading = loading == LastFmTrackAction.ADD_TO_QUEUE,
            palette = palette,
            onClick = { binding.runTrackAction(LastFmTrackAction.ADD_TO_QUEUE) },
        )
        OverflowActionItem(
            label = stringResource(Res.string.m4lf_add_to_playlist),
            icon = Res.drawable.m4lf_icon_add_playlist,
            enabled = true,
            loading = false,
            palette = palette,
            onClick = { binding.addToPlaylist() },
        )
        HorizontalDivider(color = palette.dividerColor)
        OverflowActionItem(
            label = stringResource(Res.string.m4lf_open_in_lastfm),
            icon = Res.drawable.m4_icon_link,
            enabled = !track.url.isNullOrBlank(),
            loading = false,
            palette = palette,
            onClick = { binding.openInLastFm() },
        )
        OverflowActionItem(
            label = stringResource(Res.string.m4lf_copy_song),
            icon = Res.drawable.m4lf_icon_copy,
            enabled = true,
            loading = false,
            palette = palette,
            onClick = { binding.copySong() },
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun OverflowActionItem(
    label: String,
    icon: DrawableResource,
    enabled: Boolean,
    loading: Boolean,
    palette: DashboardPalette,
    onClick: () -> Unit,
) {
    val contentColor = if (enabled) palette.textPrimary else palette.textSecondary.copy(alpha = 0.4f)
    val iconColor = if (enabled) palette.textSecondary else palette.textSecondary.copy(alpha = 0.4f)
    ListItem(
        headlineContent = {
            Text(text = label, color = contentColor)
        },
        leadingContent = {
            if (loading) {
                DashboardSpinner(modifier = Modifier.size(20.dp), color = palette.accent)
            } else {
                Icon(painter = painterResource(icon), contentDescription = null, tint = iconColor)
            }
        },
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** Shows a spinner while a section loads, or a retry when a section failed and nothing older is kept. */
@Composable
private fun SectionStatus(loaded: Boolean, refreshing: Boolean, palette: DashboardPalette, onRetry: () -> Unit) {
    if (refreshing) {
        CenteredSpinner(palette = palette)
    } else if (loaded) {
        SectionFailed(palette = palette, onRetry = onRetry)
    }
}

@Composable
private fun SectionFailed(palette: DashboardPalette, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(Res.string.m4_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry) {
            Text(stringResource(Res.string.m4_retry))
        }
    }
}

@Composable
private fun EmptyHint(text: String, palette: DashboardPalette) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = palette.emptyHintText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 24.dp),
    )
}

@Composable
private fun CenteredSpinner(palette: DashboardPalette, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        DashboardSpinner(modifier = Modifier.size(28.dp), color = palette.accent)
    }
}

/** With motion reduced the spinner shows a static arc instead of rotating. */
@Composable
private fun DashboardSpinner(modifier: Modifier, color: Color) {
    if (LocalOpticalPolicy.current.motion) {
        CircularProgressIndicator(modifier = modifier, color = color, strokeWidth = 2.dp)
    } else {
        CircularProgressIndicator(progress = { 0.7f }, modifier = modifier, color = color, strokeWidth = 2.dp)
    }
}
