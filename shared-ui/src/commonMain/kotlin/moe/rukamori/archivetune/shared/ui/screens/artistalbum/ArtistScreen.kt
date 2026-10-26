package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.ui.glass.BackdropScene
import moe.rukamori.archivetune.shared.ui.glass.LocalOpticalPolicy
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import androidx.compose.runtime.saveable.rememberSaveable
import org.jetbrains.compose.resources.stringResource

private const val ArtistSongPreviewCount = 5
private const val ArtistStatSeparator = "  •  "
private val ArtistHeroMinHeight = 560.dp
private val ArtistHorizontalPadding = 24.dp
private val ArtistContentMaxWidth = 720.dp
private val ArtistAppBarHeight = 64.dp
private val ArtistHeroTopGap = 96.dp

/**
 * The artist page. The provider page and the library view share one column: the library view replaces the provider
 * shelves while [showLocal] is set, and the FAB toggles between them as the baseline does. Nothing here plays,
 * follows or blocks directly; every effect crosses [host].
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artist: ContentRef,
    page: ArtistPageUi?,
    pageFailed: Boolean,
    library: ArtistLibraryUi?,
    blockState: ArtistBlockUiState,
    nowPlaying: ArtistAlbumNowPlaying,
    artwork: ContentArtwork,
    host: ArtistAlbumHost,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
) {
    val policy = LocalOpticalPolicy.current
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    val surfaceColor = scheme.surface
    val heroContentColor = if (surfaceColor.luminance() > 0.5f) scheme.onSurface else Color.White
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val longPress: (() -> Unit) -> Unit = { action ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        action()
    }

    // A blocked artist shows no provider actions, shelves or library content, matching the baseline strip.
    val blocked = (blockState as? ArtistBlockUiState.Known)?.blocked == true
    val shownPage = remember(page, blocked) {
        page?.let {
            if (blocked) {
                it.copy(play = null, shuffle = null, radio = null, shelves = emptyList(), releases = emptyList())
            } else {
                it
            }
        }
    }
    val shownLibrary = remember(library, blocked) {
        library?.let { if (blocked) it.copy(songs = emptyList(), albums = emptyList()) else it }
    }
    // Lazy keys are built from these ids, and a repeated key would crash the column, so repeats are dropped here.
    val songs = remember(shownLibrary) { shownLibrary?.songs.orEmpty().distinctBy { it.occurrenceId } }
    val albums = remember(shownLibrary) { shownLibrary?.albums.orEmpty().distinctBy { it.album } }
    val artistName = shownPage?.artist?.title ?: shownLibrary?.name
    val displayName = artistName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.m5_artist_unknown)
    val heroItem = ContentItem(
        ref = artist,
        title = displayName,
        artworkUri = shownPage?.artist?.artworkUri ?: shownLibrary?.artworkUri,
    )
    val followed = shownLibrary?.subscribed == true
    val nameKnown = !shownPage?.artist?.title.isNullOrBlank() || !shownLibrary?.name.isNullOrBlank()
    val blockEnabled = blockState !is ArtistBlockUiState.Loading && nameKnown
    val shareLink = shownPage?.shareLink?.takeIf { it.isNotBlank() }

    val lazyListState = rememberLazyListState()
    val revealed by rememberRevealOnScrollUp(lazyListState)
    val transparentChrome by remember {
        derivedStateOf { lazyListState.firstVisibleItemIndex == 0 && lazyListState.firstVisibleItemScrollOffset < 100 }
    }
    val titleAlpha by animateFloatAsState(
        targetValue = if (transparentChrome) 0f else 1f,
        animationSpec = if (policy.motion) tween<Float>(200) else snap<Float>(),
        label = "m5ArtistTitleAlpha",
    )

    var showLocal by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(library) { showLocal = library?.isLocal == true }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val fabVisible = songs.isNotEmpty() && shownLibrary?.isLocal != true

    val stats = remember(showLocal, shownPage, shownLibrary) { artistStatValues(showLocal, shownPage, shownLibrary) }
    val songsLabel = stringResource(Res.string.m5_artist_songs)
    val albumsLabel = stringResource(Res.string.m5_artist_albums)
    val audience = listOfNotNull(
        stats.monthlyListeners?.let { "$it ${stringResource(Res.string.m5_artist_monthly_listeners)}" },
        stats.subscribers?.let { "$it ${stringResource(Res.string.m5_artist_subscribers)}" },
    ).joinToString(ArtistStatSeparator)
    val catalog = listOfNotNull(
        stats.songCount.takeIf { it > 0 }?.let { countText(it, stats.songsHaveMore, songsLabel) },
        stats.albumCount.takeIf { it > 0 }?.let { countText(it, stats.albumsHaveMore, albumsLabel) },
    ).joinToString(ArtistStatSeparator)

    val latest = remember(showLocal, shownPage, albums) {
        latestRelease(if (showLocal) albums else shownPage?.releases.orEmpty())
    }
    val shelves = remember(shownPage) { orderedShelves(shownPage?.shelves.orEmpty()) }

    val playLocal: () -> Unit = {
        songs.firstOrNull()?.let { host.playOccurrences(songs, it.occurrenceId) }
    }
    val shuffleLocal: () -> Unit = {
        val shuffled = songs.shuffled()
        shuffled.firstOrNull()?.let { host.playOccurrences(shuffled, it.occurrenceId) }
    }

    BackdropScene(
        policy = policy,
        modifier = modifier.fillMaxSize().background(surfaceColor),
        content = {
            LazyColumn(
                state = lazyListState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            ) {
                if (shownPage == null && !showLocal) {
                    item(key = "m5-artist-pending") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = ArtistHeroMinHeight)
                                .background(scheme.surfaceContainerLow),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (pageFailed) {
                                ArtistAlbumStatus(
                                    title = stringResource(Res.string.m5_artist_load_failed),
                                    description = null,
                                    actionLabel = stringResource(Res.string.m5_artist_retry),
                                    onAction = { host.loadArtistPage(artist) },
                                )
                            } else {
                                ArtistAlbumLoading()
                            }
                        }
                    }
                } else {
                    item(key = "m5-artist-hero") {
                        ArtistHero(
                            artistItem = heroItem,
                            name = displayName,
                            audience = audience,
                            catalog = catalog,
                            followed = followed,
                            canPlay = if (showLocal) songs.isNotEmpty() else shownPage?.play != null,
                            canShuffle = if (showLocal) songs.isNotEmpty() else shownPage?.shuffle != null,
                            onFollow = { host.setLiked(artist, !followed) },
                            onPlay = {
                                if (showLocal) {
                                    playLocal()
                                } else {
                                    shownPage?.play?.let { host.playCollection(QueueLoadRequest(it, QueueCollectionKind.RADIO)) }
                                }
                            },
                            onShuffle = {
                                if (showLocal) {
                                    shuffleLocal()
                                } else {
                                    shownPage?.shuffle?.let { host.playCollection(QueueLoadRequest(it, QueueCollectionKind.RADIO)) }
                                }
                            },
                            onRadio = if (showLocal) {
                                null
                            } else {
                                shownPage?.radio?.let { radio ->
                                    { host.playCollection(QueueLoadRequest(radio, QueueCollectionKind.RADIO)) }
                                }
                            },
                            artwork = artwork,
                            surfaceColor = surfaceColor,
                            heroContentColor = heroContentColor,
                            topInset = statusTop,
                        )
                    }

                    shownPage?.description?.takeIf { it.isNotBlank() }?.let { description ->
                        item(key = "m5-artist-description") {
                            ArtistDescription(description)
                        }
                    }

                    latest?.let { release ->
                        item(key = "m5-artist-latest-${release.album.id}") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                ArtistAlbumReleaseCard(
                                    release = release,
                                    label = stringResource(Res.string.m5_artist_latest_release),
                                    metadata = releaseMetadata(release),
                                    artwork = artwork,
                                    onClick = { host.openContent(release.album) },
                                )
                            }
                        }
                    }

                    if (showLocal) {
                        if (songs.isNotEmpty()) {
                            item(key = "m5-artist-songs-title") {
                                ArtistAlbumSectionTitle(
                                    title = songsLabel,
                                    onClick = { host.openArtistSongs(artist) },
                                )
                            }
                            items(songs.take(ArtistSongPreviewCount), key = { "m5-artist-song-${it.occurrenceId}" }) { occurrence ->
                                val active = occurrence.item.ref.id == nowPlaying.songId
                                ArtistSongRow(
                                    occurrence = occurrence,
                                    active = active,
                                    playing = nowPlaying.isPlaying,
                                    artwork = artwork,
                                    onPlay = {
                                        if (active) host.togglePlayPause() else host.playSong(occurrence, songs)
                                    },
                                    onMenu = { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) },
                                    onLongPress = {
                                        longPress { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) }
                                    },
                                    modifier = if (policy.motion) Modifier.animateItem() else Modifier,
                                )
                            }
                            if (songs.size > ArtistSongPreviewCount) {
                                item(key = "m5-artist-songs-view-all") {
                                    ArtistAlbumViewAll(
                                        label = stringResource(Res.string.m5_artist_view_all),
                                        onClick = { host.openArtistSongs(artist) },
                                    )
                                }
                            }
                        }

                        if (albums.isNotEmpty()) {
                            item(key = "m5-artist-albums-title") {
                                ArtistAlbumSectionTitle(
                                    title = albumsLabel,
                                    onClick = { host.openArtistAlbums(artist) },
                                )
                            }
                            item(key = "m5-artist-albums-row") {
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    items(albums, key = { "m5-artist-album-${it.album.id}" }) { release ->
                                        ArtistAlbumCard(
                                            item = release.asContentItem(),
                                            subtitle = release.year?.toString().orEmpty(),
                                            artwork = artwork,
                                            active = release.album.id == nowPlaying.albumId,
                                            onClick = { host.openContent(release.album) },
                                            onLongClick = {
                                                longPress { host.openMenu(ArtistAlbumMenuTarget(release.album)) }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    } else if (shownPage != null) {
                        // Section keys carry the shelf index: a locale-specific response can repeat a title or a
                        // song across shelves, which must not crash the column with a duplicate key.
                        shelves.forEachIndexed { shelfIndex, shelfUi ->
                            val rows = shelfUi.shelf.page.items.distinctBy { it.item.ref }
                            if (rows.isNotEmpty()) {
                                val title = shelfUi.shelf.title
                                val more = shelfUi.more
                                item(key = "m5-artist-shelf-title-$shelfIndex") {
                                    ArtistAlbumSectionTitle(
                                        title = title,
                                        onClick = more?.let { request ->
                                            { host.openArtistSection(request, title, shelfUi.layout) }
                                        },
                                    )
                                }
                                if (shelfUi.isSongList()) {
                                    itemsIndexed(
                                        rows,
                                        key = { index, occurrence -> "m5-artist-shelf-$shelfIndex-$index-${occurrence.occurrenceId}" },
                                    ) { _, occurrence ->
                                        val active = occurrence.item.ref.id == nowPlaying.songId
                                        ArtistSongRow(
                                            occurrence = occurrence,
                                            active = active,
                                            playing = nowPlaying.isPlaying,
                                            artwork = artwork,
                                            onPlay = {
                                                if (active) host.togglePlayPause() else host.playSong(occurrence, emptyList())
                                            },
                                            onMenu = { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) },
                                            onLongPress = {
                                                longPress { host.openMenu(ArtistAlbumMenuTarget(occurrence.item.ref, occurrence)) }
                                            },
                                            modifier = if (policy.motion) Modifier.animateItem() else Modifier,
                                        )
                                    }
                                } else {
                                    item(key = "m5-artist-shelf-row-$shelfIndex") {
                                        LazyRow(
                                            contentPadding = PaddingValues(horizontal = 12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            itemsIndexed(
                                                rows,
                                                key = { index, occurrence -> "m5-artist-shelf-$shelfIndex-$index-${occurrence.occurrenceId}" },
                                            ) { _, occurrence ->
                                                ArtistShelfCard(
                                                    occurrence = occurrence,
                                                    artwork = artwork,
                                                    nowPlaying = nowPlaying,
                                                    host = host,
                                                    onLongPress = longPress,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item(key = "m5-artist-bottom") {
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        },
        chrome = {
            if (policy.recordLiquidGlass) {
                ArtistAlbumTopChrome(
                    title = artistName.orEmpty(),
                    titleAlpha = titleAlpha,
                    backDescription = stringResource(Res.string.m5_artist_back),
                    moreDescription = stringResource(Res.string.m5_artist_more_options),
                    contentColor = Color.White,
                    onBack = { host.back() },
                    onBackLongClick = { host.backToMain() },
                    onMore = { menuOpen = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(top = 8.dp),
                )
            } else {
                ArtistFlatTopBar(
                    title = artistName.orEmpty(),
                    titleAlpha = titleAlpha,
                    transparent = transparentChrome,
                    backDescription = stringResource(Res.string.m5_artist_back),
                    moreDescription = stringResource(Res.string.m5_artist_more_options),
                    onBack = { host.back() },
                    onBackLongClick = { host.backToMain() },
                    onMore = { menuOpen = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            ArtistAlbumFab(
                visible = fabVisible && revealed,
                label = if (showLocal) stringResource(Res.string.m5_artist_online) else stringResource(Res.string.m5_artist_library),
                symbol = if (showLocal) ArtistAlbumSymbol.ONLINE else ArtistAlbumSymbol.LIBRARY,
                onClick = {
                    showLocal = !showLocal
                    if (!showLocal && shownPage == null) host.loadArtistPage(artist)
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            )

            if (menuOpen) {
                ArtistOverflowSheet(
                    shareLink = shareLink,
                    blocked = blocked,
                    blockEnabled = blockEnabled,
                    onShare = { host.share(it) },
                    onCopyLink = { host.copyLink(it) },
                    onToggleBlock = {
                        host.setArtistBlocked(artist, artistName.orEmpty(), blocked = !blocked)
                    },
                    onDismiss = { menuOpen = false },
                )
            }
        },
    )
}

@Composable
private fun ArtistHero(
    artistItem: ContentItem,
    name: String,
    audience: String,
    catalog: String,
    followed: Boolean,
    canPlay: Boolean,
    canShuffle: Boolean,
    onFollow: () -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onRadio: (() -> Unit)?,
    artwork: ContentArtwork,
    surfaceColor: Color,
    heroContentColor: Color,
    topInset: Dp,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ArtistHeroMinHeight)
            .background(surfaceColor),
    ) {
        if (artistItem.artworkUri != null) {
            artwork(artistItem, Modifier.matchParentSize())
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(scheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                ArtistAlbumIcon(
                    symbol = ArtistAlbumSymbol.PERSON,
                    tint = scheme.onSurfaceVariant,
                    size = 96.dp,
                )
            }
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
                    start = ArtistHorizontalPadding,
                    top = topInset + ArtistAppBarHeight + ArtistHeroTopGap,
                    end = ArtistHorizontalPadding,
                    bottom = 24.dp,
                ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.headlineLarge,
                color = heroContentColor,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (audience.isNotEmpty()) {
                Text(
                    text = audience,
                    style = MaterialTheme.typography.labelMedium,
                    color = heroContentColor.copy(alpha = 0.62f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                )
            }
            if (catalog.isNotEmpty()) {
                Text(
                    text = catalog,
                    style = MaterialTheme.typography.labelMedium,
                    color = heroContentColor.copy(alpha = 0.62f),
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (audience.isEmpty()) 16.dp else 4.dp),
                )
            }
            ArtistAlbumPrimaryActions(
                followed = followed,
                followDescription = stringResource(Res.string.m5_artist_subscribe),
                followedDescription = stringResource(Res.string.m5_artist_subscribed),
                canPlay = canPlay,
                canShuffle = canShuffle,
                playLabel = stringResource(Res.string.m5_play),
                shuffleDescription = stringResource(Res.string.m5_shuffle),
                onFollow = onFollow,
                onPlay = onPlay,
                onShuffle = onShuffle,
                modifier = Modifier.padding(top = 12.dp),
                radioDescription = if (onRadio != null) stringResource(Res.string.m5_artist_start_radio) else null,
                onRadio = onRadio,
            )
        }
    }
}

@Composable
private fun ArtistDescription(description: String) {
    var expanded by rememberSaveable(description) { mutableStateOf(false) }
    Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = ArtistContentMaxWidth)
            .padding(horizontal = ArtistHorizontalPadding, vertical = 12.dp)
            .clickable { expanded = !expanded },
    )
}

@Composable
private fun ArtistSongRow(
    occurrence: ContentOccurrence,
    active: Boolean,
    playing: Boolean,
    artwork: ContentArtwork,
    onPlay: () -> Unit,
    onMenu: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ArtistAlbumTrackRow(
        item = occurrence.item,
        subtitle = occurrence.item.subtitle,
        artwork = artwork,
        active = active,
        playing = playing,
        onClick = onPlay,
        onLongClick = onLongPress,
        modifier = modifier,
        trailing = {
            ArtistAlbumMoreButton(
                description = stringResource(Res.string.m5_artist_more_options),
                onClick = onMenu,
            )
        },
    )
}

/** A provider card. Only a song plays; albums, artists and playlists open through the navigation owner. */
@Composable
private fun ArtistShelfCard(
    occurrence: ContentOccurrence,
    artwork: ContentArtwork,
    nowPlaying: ArtistAlbumNowPlaying,
    host: ArtistAlbumHost,
    onLongPress: (() -> Unit) -> Unit,
) {
    val ref = occurrence.item.ref
    val active = when (ref.kind) {
        ContentKind.SONG -> ref.id == nowPlaying.songId
        ContentKind.ALBUM -> ref.id == nowPlaying.albumId
        else -> false
    }
    ArtistAlbumCard(
        item = occurrence.item,
        subtitle = occurrence.item.subtitle,
        artwork = artwork,
        active = active,
        onClick = {
            when (ref.kind) {
                ContentKind.SONG -> if (active) host.togglePlayPause() else host.playSong(occurrence, emptyList())
                ContentKind.ALBUM, ContentKind.ARTIST, ContentKind.PLAYLIST -> host.openContent(ref)
                ContentKind.PODCAST, ContentKind.EPISODE -> Unit
            }
        },
        onLongClick = { onLongPress { host.openMenu(ArtistAlbumMenuTarget(ref, occurrence)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ArtistFlatTopBar(
    title: String,
    titleAlpha: Float,
    transparent: Boolean,
    backDescription: String,
    moreDescription: String,
    onBack: () -> Unit,
    onBackLongClick: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val iconTint = if (transparent) Color.White else scheme.onSurface
    val colors = if (transparent) {
        TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            navigationIconContentColor = Color.White,
            titleContentColor = Color.White,
            actionIconContentColor = Color.White,
        )
    } else {
        TopAppBarDefaults.topAppBarColors(
            containerColor = scheme.surface,
            navigationIconContentColor = scheme.onSurface,
            titleContentColor = scheme.onSurface,
            actionIconContentColor = scheme.onSurface,
        )
    }
    TopAppBar(
        title = {
            Text(
                text = title,
                modifier = Modifier.alpha(titleAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        modifier = modifier,
        navigationIcon = {
            // A plain box carries the long press, which IconButton would swallow.
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .combinedClickable(onClick = onBack, onLongClick = onBackLongClick),
                contentAlignment = Alignment.Center,
            ) {
                ArtistAlbumIcon(symbol = ArtistAlbumSymbol.BACK, tint = iconTint, description = backDescription)
            }
        },
        actions = {
            IconButton(onClick = onMore) {
                ArtistAlbumIcon(symbol = ArtistAlbumSymbol.MORE_HORIZONTAL, tint = iconTint, description = moreDescription)
            }
        },
        colors = colors,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistOverflowSheet(
    shareLink: String?,
    blocked: Boolean,
    blockEnabled: Boolean,
    onShare: (String) -> Unit,
    onCopyLink: (String) -> Unit,
    onToggleBlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ArtistAlbumSheetItem(
                text = stringResource(Res.string.m5_share),
                symbol = ArtistAlbumSymbol.SHARE,
                enabled = shareLink != null,
                onClick = {
                    shareLink?.let(onShare)
                    onDismiss()
                },
            )
            ArtistAlbumSheetItem(
                text = stringResource(Res.string.m5_artist_copy_link),
                symbol = ArtistAlbumSymbol.LINK,
                enabled = shareLink != null,
                onClick = {
                    shareLink?.let(onCopyLink)
                    onDismiss()
                },
            )
            ArtistAlbumSheetItem(
                text = stringResource(if (blocked) Res.string.m5_artist_unblock else Res.string.m5_artist_block),
                symbol = ArtistAlbumSymbol.BLOCK,
                enabled = blockEnabled,
                onClick = {
                    onToggleBlock()
                    onDismiss()
                },
            )
        }
    }
}

@Composable
private fun releaseMetadata(release: ArtistReleaseUi): String {
    val typeLabel = stringResource(
        when (release.type) {
            ArtistReleaseType.ALBUM -> Res.string.m5_artist_release_type_album
            ArtistReleaseType.SINGLE -> Res.string.m5_artist_release_type_single
            ArtistReleaseType.EP -> Res.string.m5_artist_release_type_ep
        },
    )
    val year = release.year
    return if (year != null) {
        stringResource(Res.string.m5_artist_release_metadata, typeLabel, year)
    } else {
        typeLabel
    }
}

private fun countText(count: Int, hasMore: Boolean, label: String): String {
    val value = formatCompactCount(count.toLong())
    return "${if (hasMore) "$value+" else value} $label"
}
