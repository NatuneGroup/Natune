/*
 * The Spotify page layout is ported from YumaPlayer (2026) | Modified work by MuwMx.
 */
package moe.rukamori.archivetune.shared.ui.home.variants

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.home.HomeFeedSectionHeader
import moe.rukamori.archivetune.shared.ui.home.SpotifyHomeLayout
import moe.rukamori.archivetune.shared.ui.home.spotifyHomeSurface
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.runtime.setValue

/**
 * A Spotify shelf title. The variant localises the feed's known keys itself, so the owner passes the meaning and never
 * a string. [Plain] carries a title the feed sends as text, shown unchanged.
 */
sealed interface SpotifyShelfTitle {
    data class BecauseYouLike(val artistName: String) : SpotifyShelfTitle

    data object TopTracks : SpotifyShelfTitle

    data object TopArtists : SpotifyShelfTitle

    data object MadeForYou : SpotifyShelfTitle

    data object Discover : SpotifyShelfTitle

    data object YourPlaylists : SpotifyShelfTitle

    data object NewReleases : SpotifyShelfTitle

    data class Plain(val text: String) : SpotifyShelfTitle
}

/**
 * One Spotify shelf in feed order. A track shelf is two rows of track tiles, and a card shelf is one rail that holds
 * albums, playlists and artists together, as Spotify sends them.
 */
sealed interface SpotifyHomeShelf {
    val key: String
    val title: SpotifyShelfTitle

    data class Tracks(
        override val key: String,
        override val title: SpotifyShelfTitle,
        val tracks: List<ContentOccurrence>,
    ) : SpotifyHomeShelf

    data class Cards(
        override val key: String,
        override val title: SpotifyShelfTitle,
        val items: List<ContentOccurrence>,
    ) : SpotifyHomeShelf
}

/**
 * The Spotify page as the owner delivers it. [recentItems] are playlists and albums for the two-column recent grid,
 * and [frequentArtists] are artists for the frequent grid; each grid shows the first eight. [resolving] is the item the
 * owner is matching or opening right now, and it shows a spinner on that tile.
 */
data class SpotifyHomeContent(
    val recentItems: List<ContentItem> = emptyList(),
    val frequentArtists: List<ContentItem> = emptyList(),
    val shelves: List<SpotifyHomeShelf> = emptyList(),
    val resolving: ContentRef? = null,
    val isRefreshing: Boolean = false,
)

/** The Spotify page states. [Failed.notConnected] offers the YouTube Music route in place of retry, as the baseline did. */
sealed interface SpotifyHomeUi {
    data object Loading : SpotifyHomeUi

    data object Empty : SpotifyHomeUi

    data class Failed(val detail: String? = null, val notConnected: Boolean = false) : SpotifyHomeUi

    data class Ready(val content: SpotifyHomeContent) : SpotifyHomeUi
}

/**
 * The owner's answers to the Spotify page. [play] receives a track-shelf tap as [HomeVariantPlay.Shelf] with toggling on,
 * and the owner resolves the track to a playable match, or toggles when that track already plays. [openPlaylist] opens a
 * playlist and owns the DJ hand-off to Spotify's web page. [select] resolves an album or artist to its page while the
 * owner shows [SpotifyHomeContent.resolving]. [switchToYoutube], [refresh] answer the empty, failed and pull states.
 */
class SpotifyHomeActions(
    val play: (HomeVariantPlay) -> Unit,
    val openPlaylist: (ContentRef) -> Unit,
    val select: (ContentItem) -> Unit,
    val switchToYoutube: () -> Unit,
    val refresh: () -> Unit,
)

private val SpotifyCardWidth = 150.dp
private val SpotifyArtistSize = 140.dp
private val SpotifyTrackWidth = 240.dp
private val SpotifyTrackRowHeight = 128.dp
private val SpotifyEdgePadding = 16.dp
private val SpotifyCardSpacing = 12.dp
private val SpotifyThumbnailCorner = 8.dp
private const val SpotifyQuickGridMax = 8
private val SpotifyCardKinds = setOf(ContentKind.ALBUM, ContentKind.PLAYLIST, ContentKind.ARTIST)

/**
 * The Spotify home page. [blurEffects] is the host's blur preference, passed to [SpotifyHomeLayout] unchanged. [artwork]
 * draws every item's artwork, and [modifier] carries the host's nested-scroll connection when the header collapses.
 * The page is laid out at [LocalShellContentPadding] unless [contentPadding] says otherwise. [thumbnailAspectRatio] gives
 * each album and playlist card its frame ratio; the default keeps every card square. Artists stay circles, and the track
 * tiles and grid cells keep their fixed squares.
 */
@Composable
fun SpotifyHomeVariant(
    state: SpotifyHomeUi,
    actions: SpotifyHomeActions,
    artwork: ContentArtwork,
    nowPlaying: HomeVariantNowPlaying,
    blurEffects: Boolean,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    listState: LazyListState = rememberLazyListState(),
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio = { 1f },
) {
    SpotifyHomeLayout(blurEffects = blurEffects, modifier = modifier.fillMaxSize()) {
        when (state) {
            SpotifyHomeUi.Loading ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = true,
                    icon = null,
                    message = null,
                    onRetry = null,
                )

            SpotifyHomeUi.Empty ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = false,
                    icon = Res.drawable.m4_icon_music_note,
                    message = stringResource(Res.string.m4hv_no_results),
                    onRetry = actions.refresh,
                )

            is SpotifyHomeUi.Failed ->
                if (state.notConnected) {
                    HomeVariantStatePane(
                        contentPadding = contentPadding,
                        showLoading = false,
                        icon = Res.drawable.m4_icon_info,
                        message = stringResource(Res.string.m4hv_spotify_not_connected),
                        onRetry = null,
                        actionLabel = stringResource(Res.string.m4hv_open_youtube_music),
                        onAction = actions.switchToYoutube,
                    )
                } else {
                    HomeVariantStatePane(
                        contentPadding = contentPadding,
                        showLoading = false,
                        icon = Res.drawable.m4_icon_info,
                        message = state.detail ?: stringResource(Res.string.m4hv_home_failed),
                        onRetry = actions.refresh,
                    )
                }

            is SpotifyHomeUi.Ready ->
                SpotifyHomeFeed(
                    content = state.content,
                    actions = actions,
                    artwork = artwork,
                    nowPlaying = nowPlaying,
                    contentPadding = contentPadding,
                    listState = listState,
                    thumbnailAspectRatio = thumbnailAspectRatio,
                )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SpotifyHomeFeed(
    content: SpotifyHomeContent,
    actions: SpotifyHomeActions,
    artwork: ContentArtwork,
    nowPlaying: HomeVariantNowPlaying,
    contentPadding: PaddingValues,
    listState: LazyListState,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
) {
    PullToRefreshBox(
        isRefreshing = content.isRefreshing,
        onRefresh = actions.refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "spotify_recent_panel", contentType = "recent_panel") {
                SpotifyRecentPanel(
                    recentItems = content.recentItems,
                    frequentArtists = content.frequentArtists,
                    artwork = artwork,
                    resolving = content.resolving,
                    actions = actions,
                )
            }

            // Sections render in the order the feed returns them. The baseline once hoisted the first track shelf under
            // an extra header, and that reorder made the page differ from the feed, so the order is kept as sent.
            content.shelves.forEachIndexed { index, shelf ->
                val shelfKey = "${shelf.key}_$index"
                item(key = "spotify_section_title_$shelfKey", contentType = "section_header") {
                    HomeFeedSectionHeader(title = spotifyShelfTitle(shelf.title))
                }
                item(key = "spotify_section_content_$shelfKey", contentType = "section_content") {
                    when (shelf) {
                        is SpotifyHomeShelf.Tracks ->
                            SpotifyTrackShelf(
                                shelfTitle = spotifyShelfTitle(shelf.title),
                                tracks = shelf.tracks,
                                nowPlaying = nowPlaying,
                                artwork = artwork,
                                resolving = content.resolving,
                                onPlay = actions.play,
                            )

                        is SpotifyHomeShelf.Cards ->
                            SpotifyCardShelf(
                                items = shelf.items,
                                artwork = artwork,
                                resolving = content.resolving,
                                actions = actions,
                                thumbnailAspectRatio = thumbnailAspectRatio,
                            )
                    }
                }
            }
        }
    }
}

@Composable
private fun spotifyShelfTitle(title: SpotifyShelfTitle): String =
    when (title) {
        is SpotifyShelfTitle.BecauseYouLike ->
            stringResource(Res.string.m4hv_spotify_because_you_like, title.artistName)
        SpotifyShelfTitle.TopTracks -> stringResource(Res.string.m4hv_spotify_top_tracks)
        SpotifyShelfTitle.TopArtists -> stringResource(Res.string.m4hv_spotify_top_artists)
        SpotifyShelfTitle.MadeForYou -> stringResource(Res.string.m4hv_spotify_made_for_you)
        SpotifyShelfTitle.Discover -> stringResource(Res.string.m4hv_spotify_discover)
        SpotifyShelfTitle.YourPlaylists -> stringResource(Res.string.m4hv_spotify_your_playlists)
        SpotifyShelfTitle.NewReleases -> stringResource(Res.string.m4hv_spotify_new_releases)
        is SpotifyShelfTitle.Plain -> title.text
    }

/** Playlists open their page; albums and artists go through the owner's [SpotifyHomeActions.select] to resolve first. */
private fun SpotifyHomeActions.openItem(item: ContentItem) {
    if (item.ref.kind == ContentKind.PLAYLIST) {
        openPlaylist(item.ref)
    } else {
        select(item)
    }
}

/**
 * Two rows of track tiles. A tap on the playing track toggles play and pause, and any other tap starts the shelf from
 * the tapped track, so the owner builds the queue from the shelf in order.
 */
@Composable
private fun SpotifyTrackShelf(
    shelfTitle: String,
    tracks: List<ContentOccurrence>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    resolving: ContentRef?,
    onPlay: (HomeVariantPlay) -> Unit,
) {
    if (tracks.isEmpty()) return
    LazyHorizontalGrid(
        rows = GridCells.Fixed(2),
        contentPadding = PaddingValues(horizontal = SpotifyEdgePadding),
        modifier = Modifier.fillMaxWidth().height(SpotifyTrackRowHeight * 2),
    ) {
        gridItemsIndexed(
            items = tracks,
            key = { index, occurrence -> "spotify_track_${occurrence.occurrenceId}_$index" },
            contentType = { _, _ -> "spotify_track" },
        ) { index, occurrence ->
            SpotifyTrackTile(
                occurrence = occurrence,
                isActive = nowPlaying.isActive(occurrence.item.ref),
                isPlaying = nowPlaying.isPlaying,
                isResolving = resolving == occurrence.item.ref,
                artwork = artwork,
                onClick = {
                    onPlay(
                        HomeVariantPlay.Shelf(
                            title = shelfTitle,
                            occurrences = tracks,
                            startIndex = index,
                            toggleIfActive = true,
                        ),
                    )
                },
                modifier = Modifier.width(SpotifyTrackWidth).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun SpotifyTrackTile(
    occurrence: ContentOccurrence,
    isActive: Boolean,
    isPlaying: Boolean,
    isResolving: Boolean,
    artwork: ContentArtwork,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = occurrence.item
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .spotifyPressClickable(onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        artwork(item, Modifier.size(56.dp).clip(RoundedCornerShape(SpotifyThumbnailCorner)))
        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isResolving) {
            SpotifySelectionIndicator()
        } else if (isActive && isPlaying) {
            Icon(
                painter = painterResource(Res.drawable.m4_icon_listening),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A rail of albums, playlists and artists. Other kinds never reach a card shelf, so the rail drops them. */
@Composable
private fun SpotifyCardShelf(
    items: List<ContentOccurrence>,
    artwork: ContentArtwork,
    resolving: ContentRef?,
    actions: SpotifyHomeActions,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
) {
    val cards = remember(items) { items.filter { it.item.ref.kind in SpotifyCardKinds } }
    LazyRow(
        contentPadding = PaddingValues(horizontal = SpotifyEdgePadding),
        horizontalArrangement = Arrangement.spacedBy(SpotifyCardSpacing),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(
            items = cards,
            key = { index, occurrence -> "spotify_card_${occurrence.occurrenceId}_$index" },
            contentType = { _, occurrence -> occurrence.item.ref.kind.name },
        ) { _, occurrence ->
            val item = occurrence.item
            val isResolving = resolving == item.ref
            if (item.ref.kind == ContentKind.ARTIST) {
                SpotifyArtistCard(
                    item = item,
                    artwork = artwork,
                    isResolving = isResolving,
                    onClick = { actions.openItem(item) },
                )
            } else {
                SpotifyMediaCard(
                    item = item,
                    artwork = artwork,
                    isResolving = isResolving,
                    onClick = { actions.openItem(item) },
                    thumbnailAspectRatio = thumbnailAspectRatio.frameRatioFor(item),
                )
            }
        }
    }
}

/**
 * An album or playlist card: a surface tile with a square slot for the thumbnail, a bold title and a secondary subtitle.
 * A frame that is not square ([thumbnailAspectRatio], from [frameRatioFor]) sits centred in that slot.
 */
@Composable
private fun SpotifyMediaCard(
    item: ContentItem,
    artwork: ContentArtwork,
    isResolving: Boolean,
    onClick: () -> Unit,
    thumbnailAspectRatio: Float = 1f,
) {
    Column(
        modifier = Modifier
            .width(SpotifyCardWidth)
            .spotifyHomeSurface(RoundedCornerShape(24.dp))
            .spotifyPressClickable(onClick)
            // The baseline's 8dp tile inset and 12dp grid inset together.
            .padding(20.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
        ) {
            artwork(
                item,
                Modifier
                    .aspectRatio(thumbnailAspectRatio)
                    .clip(RoundedCornerShape(SpotifyThumbnailCorner)),
            )
            if (isResolving) SpotifySelectionIndicator()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().basicMarquee(),
        )
        Text(
            text = item.subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.secondary,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.fillMaxWidth().basicMarquee(),
        )
    }
}

/** An artist card: a circular portrait with the name beneath it. */
@Composable
private fun SpotifyArtistCard(
    item: ContentItem,
    artwork: ContentArtwork,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(SpotifyArtistSize)
            .spotifyPressClickable(onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            artwork(item, Modifier.size(SpotifyArtistSize).clip(CircleShape))
            if (isResolving) SpotifySelectionIndicator()
        }
        Text(
            text = item.title,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(top = 4.dp).basicMarquee(),
        )
    }
}

/** The recent grid of playlists and albums, then the frequent grid of artists, each up to eight cells. */
@Composable
private fun SpotifyRecentPanel(
    recentItems: List<ContentItem>,
    frequentArtists: List<ContentItem>,
    artwork: ContentArtwork,
    resolving: ContentRef?,
    actions: SpotifyHomeActions,
) {
    Column {
        if (recentItems.isNotEmpty()) {
            HomeFeedSectionHeader(title = stringResource(Res.string.m4hv_recently_played))
            SpotifyQuickGrid(items = recentItems) { item ->
                SpotifyQuickGridCell(
                    item = item,
                    artwork = artwork,
                    isResolving = resolving == item.ref,
                    onClick = { actions.openItem(item) },
                )
            }
        }
        if (frequentArtists.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            HomeFeedSectionHeader(title = stringResource(Res.string.m4hv_frequently_listened))
            SpotifyQuickGrid(items = frequentArtists) { artist ->
                SpotifyQuickGridCell(
                    item = artist,
                    artwork = artwork,
                    isResolving = resolving == artist.ref,
                    onClick = { actions.select(artist) },
                )
            }
        }
    }
}

/** Two columns of up to [SpotifyQuickGridMax] cells, with the baseline's 12dp side inset and 8dp gutters. */
@Composable
private fun SpotifyQuickGrid(
    items: List<ContentItem>,
    itemContent: @Composable (ContentItem) -> Unit,
) {
    val displayItems = items.take(SpotifyQuickGridMax)
    if (displayItems.isEmpty()) return
    val rows = displayItems.chunked(2)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        rows.forEachIndexed { rowIndex, rowItems ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowItems.forEach { item ->
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        itemContent(item)
                    }
                }
                repeat(2 - rowItems.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            if (rowIndex < rows.lastIndex) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** A grid cell: a 56dp thumbnail (a circle for artists, left-rounded otherwise), a two-line title and the spinner. */
@Composable
private fun SpotifyQuickGridCell(
    item: ContentItem,
    artwork: ContentArtwork,
    isResolving: Boolean,
    onClick: () -> Unit,
) {
    val isArtist = item.ref.kind == ContentKind.ARTIST
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .spotifyHomeSurface(RoundedCornerShape(SpotifyThumbnailCorner))
            .spotifyPressClickable(onClick),
    ) {
        artwork(
            item,
            Modifier
                .size(56.dp)
                .clip(
                    if (isArtist) {
                        CircleShape
                    } else {
                        RoundedCornerShape(topStart = SpotifyThumbnailCorner, bottomStart = SpotifyThumbnailCorner)
                    },
                ),
        )
        Text(
            text = item.title,
            style = MaterialTheme.typography.labelMedium.copy(
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
        if (isResolving) SpotifySelectionIndicator()
    }
}

/** The spinner that marks the item the owner is resolving or opening. */
@Composable
private fun SpotifySelectionIndicator() {
    val loadingLabel = stringResource(Res.string.m4hv_loading)
    CircularProgressIndicator(
        strokeWidth = 2.dp,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, CircleShape)
            .padding(6.dp)
            .size(20.dp)
            .semantics { contentDescription = loadingLabel },
    )
}

/**
 * A click target that eases to 96% while pressed, the feel of the baseline press-scale. The scale wraps the whole
 * target, so the ripple and the tile move together.
 */
@Composable
private fun Modifier.spotifyPressClickable(onClick: () -> Unit): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        label = "spotifyPressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = LocalIndication.current,
            onClick = onClick,
        )
}
