package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentDownload
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.glass.BackdropScene
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.stringResource

private val AlbumHeroMinHeight = 460.dp
private val AlbumHorizontalPadding = 24.dp
private val AlbumAppBarHeight = 64.dp
private val AlbumHeroTopGap = 48.dp
private const val AlbumStatSeparator = "  •  "

/**
 * An album page. The track list is queued as a whole only when [AlbumPageUi.songsComplete] is set; a partial list is
 * listed but never queued as the album, and a single track then starts its own provider watch queue. Downloads are
 * decided from [downloads]; the host resolves quality and performs every write. Long press opens a selection mode,
 * whose menu receives the selected songs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumScreen(
    album: ContentRef,
    page: AlbumPageUi?,
    loadState: AlbumLoadState,
    downloads: List<ContentDownload>,
    nowPlaying: ArtistAlbumNowPlaying,
    artwork: ContentArtwork,
    host: ArtistAlbumHost,
    modifier: Modifier = Modifier,
    canvas: AlbumCanvasUi? = null,
    canvasSlot: AlbumCanvasSlot? = null,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val policy = LocalOpticalPolicy.current
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val surfaceColor = scheme.surface
    val heroContentColor = if (surfaceColor.luminance() > 0.5f) scheme.onSurface else Color.White
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    val title = page?.album?.title.orEmpty()
    val songs = remember(page) { page?.songs?.distinctBy { it.occurrenceId }.orEmpty() }
    val songsComplete = page?.songsComplete == true
    val versions = remember(page) { page?.otherVersions?.distinctBy { it.ref }.orEmpty() }
    val refs = remember(songs) { songs.map { it.item.ref } }
    val downloadState = remember(refs, downloads) { albumDownloadState(refs, downloads) }
    val totalDurationMs = remember(songs) {
        songs.mapNotNull { it.item.durationMs }.takeIf { it.isNotEmpty() }?.sum()
    }
    val songCount = songs.size
    val metadata = listOfNotNull(
        page?.year?.toString(),
        if (songCount > 0) songCountText(songCount) else null,
        formatTrackDuration(totalDurationMs).takeIf { it.isNotEmpty() },
    ).joinToString(AlbumStatSeparator)
    // Looping artwork is motion; reduced motion keeps the static cover even when the host supplies a canvas.
    val shownCanvas = canvas?.takeIf { policy.motion }

    var selecting by remember(album) { mutableStateOf(false) }
    val selectedIds = remember(album) { mutableStateListOf<String>() }
    val selectedSongs = songs.filter { it.occurrenceId in selectedIds }

    val listState = rememberLazyListState()
    val transparentChrome by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 100 }
    }
    val titleAlpha by animateFloatAsState(
        targetValue = if (transparentChrome) 0f else 1f,
        animationSpec = if (policy.motion) tween<Float>(200) else snap<Float>(),
        label = "m5AlbumTitleAlpha",
    )

    val retry: () -> Unit = { host.loadAlbumPage(album) }
    val playAll: () -> Unit = {
        if (songsComplete) {
            songs.firstOrNull()?.let { host.playOccurrences(songs, it.occurrenceId) }
        }
    }
    val shuffleAll: () -> Unit = {
        if (songsComplete) {
            val shuffled = songs.shuffled()
            shuffled.firstOrNull()?.let { host.playOccurrences(shuffled, it.occurrenceId) }
        }
    }
    val onDownload: () -> Unit = {
        if (refs.isNotEmpty()) {
            when (downloadState) {
                AlbumDownloadState.None -> {
                    host.downloadSongs(refs)
                    host.openDownloads()
                }
                is AlbumDownloadState.Partial, AlbumDownloadState.Completed -> host.removeDownloads(refs)
            }
        }
    }
    val toggleSelection: (ContentOccurrence) -> Unit = { occurrence ->
        val id = occurrence.occurrenceId
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    BackdropScene(
        policy = policy,
        modifier = modifier.fillMaxSize().background(surfaceColor),
        content = {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                when (loadState) {
                    AlbumLoadState.Loading -> {
                        item(key = "m5-album-loading") {
                            ArtistAlbumLoading()
                        }
                    }
                    is AlbumLoadState.Error -> {
                        item(key = "m5-album-error") {
                            // A missing album has nothing to retry; any other failure can be retried.
                            ArtistAlbumStatus(
                                title = stringResource(
                                    if (loadState.notFound) Res.string.m5_album_not_found else Res.string.m5_album_error_unknown,
                                ),
                                description = if (loadState.notFound) {
                                    stringResource(Res.string.m5_album_not_found_description)
                                } else {
                                    null
                                },
                                actionLabel = if (loadState.notFound) null else stringResource(Res.string.m5_album_retry),
                                onAction = if (loadState.notFound) null else retry,
                            )
                        }
                    }
                    AlbumLoadState.Empty -> {
                        if (page != null) {
                            item(key = "m5-album-hero") {
                                AlbumHero(
                                    album = page.album,
                                    artists = page.artists,
                                    metadata = metadata,
                                    artwork = artwork,
                                    canvas = shownCanvas,
                                    canvasSlot = canvasSlot,
                                    surfaceColor = surfaceColor,
                                    heroContentColor = heroContentColor,
                                    topInset = statusTop,
                                    onArtist = { host.openContent(it) },
                                )
                            }
                        }
                        item(key = "m5-album-empty") {
                            ArtistAlbumStatus(
                                title = stringResource(Res.string.m5_album_empty),
                                description = stringResource(Res.string.m5_album_empty_description),
                                actionLabel = null,
                                onAction = null,
                            )
                        }
                    }
                    AlbumLoadState.Content -> {
                        if (page == null) {
                            item(key = "m5-album-loading") {
                                ArtistAlbumLoading()
                            }
                        } else {
                            item(key = "m5-album-hero") {
                                AlbumHero(
                                    album = page.album,
                                    artists = page.artists,
                                    metadata = metadata,
                                    artwork = artwork,
                                    canvas = shownCanvas,
                                    canvasSlot = canvasSlot,
                                    surfaceColor = surfaceColor,
                                    heroContentColor = heroContentColor,
                                    topInset = statusTop,
                                    onArtist = { host.openContent(it) },
                                )
                            }
                            item(key = "m5-album-actions") {
                                AlbumActions(
                                    bookmarked = page.bookmarked,
                                    canPlay = songsComplete && songCount > 0,
                                    download = downloadState,
                                    onBookmark = { host.setLiked(album, !page.bookmarked) },
                                    onPlay = playAll,
                                    onShuffle = shuffleAll,
                                    onDownload = onDownload,
                                    onMore = { host.openMenu(ArtistAlbumMenuTarget(album)) },
                                )
                            }
                            if (songs.isEmpty()) {
                                item(key = "m5-album-empty") {
                                    ArtistAlbumStatus(
                                        title = stringResource(Res.string.m5_album_empty),
                                        description = stringResource(Res.string.m5_album_empty_description),
                                        actionLabel = null,
                                        onAction = null,
                                    )
                                }
                            } else {
                                itemsIndexed(
                                    songs,
                                    key = { _, occurrence -> "m5-album-song-${occurrence.occurrenceId}" },
                                ) { _, occurrence ->
                                    val active = occurrence.item.ref.id == nowPlaying.songId
                                    val selected = occurrence.occurrenceId in selectedIds
                                    AlbumSongRow(
                                        occurrence = occurrence,
                                        active = active,
                                        playing = nowPlaying.isPlaying,
                                        artwork = artwork,
                                        selecting = selecting,
                                        selected = selected,
                                        onClick = {
                                            when {
                                                selecting -> toggleSelection(occurrence)
                                                active -> host.togglePlayPause()
                                                songsComplete -> host.playOccurrences(songs, occurrence.occurrenceId)
                                                else -> host.playSong(occurrence, emptyList())
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            selecting = true
                                            if (!selected) selectedIds.add(occurrence.occurrenceId)
                                        },
                                        onMore = {
                                            host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence))
                                        },
                                    )
                                }
                            }
                            if (versions.isNotEmpty()) {
                                item(key = "m5-album-versions-title") {
                                    ArtistAlbumSectionTitle(
                                        title = stringResource(Res.string.m5_album_other_versions),
                                        onClick = null,
                                    )
                                }
                                item(key = "m5-album-versions-row") {
                                    LazyRow(
                                        contentPadding = PaddingValues(horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        items(versions, key = { "m5-album-version-${it.ref.id}" }) { version ->
                                            ArtistAlbumCard(
                                                item = version,
                                                subtitle = version.subtitle,
                                                artwork = artwork,
                                                active = version.ref.id == nowPlaying.albumId,
                                                onClick = { host.openContent(version.ref) },
                                                onLongClick = { host.openMenu(ArtistAlbumMenuTarget(version.ref)) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                item(key = "m5-album-bottom") {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        },
        chrome = {
            if (selecting) {
                AlbumSelectionTopBar(
                    count = selectedSongs.size,
                    onClose = {
                        selecting = false
                        selectedIds.clear()
                    },
                    onSelectAll = {
                        selectedIds.clear()
                        selectedIds.addAll(songs.map { it.occurrenceId })
                    },
                    onMore = { host.openSelectionMenu(selectedSongs) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            } else if (policy.recordLiquidGlass) {
                ArtistAlbumTopChrome(
                    title = title,
                    titleAlpha = titleAlpha,
                    backDescription = stringResource(Res.string.m5_artist_back),
                    moreDescription = stringResource(Res.string.m5_artist_more_options),
                    contentColor = Color.White,
                    onBack = { host.back() },
                    onBackLongClick = { host.backToMain() },
                    onMore = { host.openMenu(ArtistAlbumMenuTarget(album)) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp),
                )
            } else {
                ArtistFlatTopBar(
                    title = title,
                    titleAlpha = titleAlpha,
                    transparent = transparentChrome,
                    backDescription = stringResource(Res.string.m5_artist_back),
                    moreDescription = stringResource(Res.string.m5_artist_more_options),
                    onBack = { host.back() },
                    onBackLongClick = { host.backToMain() },
                    onMore = { host.openMenu(ArtistAlbumMenuTarget(album)) },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        },
    )
}

@Composable
private fun AlbumHero(
    album: ContentItem,
    artists: List<ContentItem>,
    metadata: String,
    artwork: ContentArtwork,
    canvas: AlbumCanvasUi?,
    canvasSlot: AlbumCanvasSlot?,
    surfaceColor: Color,
    heroContentColor: Color,
    topInset: Dp,
    onArtist: (ContentRef) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = AlbumHeroMinHeight)
            .background(surfaceColor),
    ) {
        if (album.artworkUri != null) {
            artwork(album, Modifier.matchParentSize())
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(scheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.ALBUM,
                    tint = scheme.onSurfaceVariant,
                    size = 96.dp,
                )
            }
        }

        if (canvas != null && canvasSlot != null) {
            canvasSlot(canvas, Modifier.matchParentSize())
        }

        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.42f),
                        0.18f to Color.Transparent,
                        0.42f to Color.Transparent,
                        0.72f to surfaceColor.copy(alpha = 0.78f),
                        1f to surfaceColor,
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(
                    start = AlbumHorizontalPadding,
                    top = topInset + AlbumAppBarHeight + AlbumHeroTopGap,
                    end = AlbumHorizontalPadding,
                    bottom = 24.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = album.title,
                style = MaterialTheme.typography.headlineLarge,
                color = heroContentColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            artists.forEach { artist ->
                Text(
                    text = artist.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = heroContentColor,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { onArtist(artist.ref) },
                )
            }
            if (metadata.isNotEmpty()) {
                Text(
                    text = metadata,
                    style = MaterialTheme.typography.labelMedium,
                    color = heroContentColor.copy(alpha = 0.62f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun AlbumActions(
    bookmarked: Boolean,
    canPlay: Boolean,
    download: AlbumDownloadState,
    onBookmark: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onMore: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ArtistAlbumPrimaryActions(
            followed = bookmarked,
            followDescription = stringResource(Res.string.m5_album_add_to_library),
            followedDescription = stringResource(Res.string.m5_album_remove_from_library),
            canPlay = canPlay,
            canShuffle = canPlay,
            playLabel = stringResource(Res.string.m5_play),
            shuffleDescription = stringResource(Res.string.m5_shuffle),
            onFollow = onBookmark,
            onPlay = onPlay,
            onShuffle = onShuffle,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            AlbumDownloadButton(state = download, onClick = onDownload)
            Spacer(modifier = Modifier.width(8.dp))
            ArtistAlbumMoreButton(
                description = stringResource(Res.string.m5_artist_more_options),
                onClick = onMore,
            )
        }
    }
}

/**
 * Download control. Partial shows a static determinate ring (no spinning loop, for reduced motion); tapping a partial
 * or completed album removes its downloads, as the baseline does, while an absent album downloads what is missing.
 */
@Composable
private fun AlbumDownloadButton(state: AlbumDownloadState, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        when (state) {
            AlbumDownloadState.None -> {
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.DOWNLOAD,
                    tint = scheme.onSurface,
                    description = stringResource(Res.string.m5_album_download),
                )
            }
            is AlbumDownloadState.Partial -> {
                Box(
                    modifier = Modifier.size(28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.matchParentSize(),
                        strokeWidth = 2.dp,
                    )
                    ArtistAlbumIcon(
                        symbol = ArtistAlbumSymbol.DOWNLOAD,
                        tint = scheme.onSurface,
                        size = 14.dp,
                        description = stringResource(
                            if (state.paused) Res.string.m5_album_download_paused else Res.string.m5_album_downloading,
                        ),
                    )
                }
            }
            AlbumDownloadState.Completed -> {
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.DOWNLOAD_DONE,
                    tint = scheme.primary,
                    description = stringResource(Res.string.m5_album_downloaded),
                )
            }
        }
    }
}

@Composable
private fun AlbumSongRow(
    occurrence: ContentOccurrence,
    active: Boolean,
    playing: Boolean,
    artwork: ContentArtwork,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMore: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    ArtistAlbumTrackRow(
        item = occurrence.item,
        subtitle = occurrence.item.subtitle,
        artwork = artwork,
        active = active,
        playing = playing,
        onClick = onClick,
        onLongClick = onLongClick,
        selected = selecting && selected,
        trailing = {
            when {
                !selecting -> ArtistAlbumMoreButton(
                    description = stringResource(Res.string.m5_artist_more_options),
                    onClick = onMore,
                )
                selected -> ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.CHECK,
                    tint = scheme.primary,
                    size = 24.dp,
                )
                else -> Unit
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumSelectionTopBar(
    count: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    TopAppBar(
        title = {
            Text(
                text = stringResource(Res.string.m5_album_selected_count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onClose) {
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.CLOSE,
                    tint = scheme.onSurface,
                    description = stringResource(Res.string.m5_album_close),
                )
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.SELECT_ALL,
                    tint = scheme.onSurface,
                    description = stringResource(Res.string.m5_album_select_all),
                )
            }
            IconButton(onClick = onMore, enabled = count > 0) {
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.MORE_VERTICAL,
                    tint = scheme.onSurface,
                    description = stringResource(Res.string.m5_artist_more_options),
                )
            }
        },
    )
}
