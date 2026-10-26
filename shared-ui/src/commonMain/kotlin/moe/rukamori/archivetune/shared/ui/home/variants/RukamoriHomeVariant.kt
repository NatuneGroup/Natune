package moe.rukamori.archivetune.shared.ui.home.variants

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.home.HomeFeedGutter
import moe.rukamori.archivetune.shared.ui.home.HomeFeedSectionHeader
import moe.rukamori.archivetune.shared.ui.home.HomeFeedShelfCard
import moe.rukamori.archivetune.shared.ui.home.HomeShelfCardSpacing
import moe.rukamori.archivetune.shared.ui.home.HomeShelfCardWidth
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The Rukamori page as the owner delivers it. Each state is complete; the variant never invents a section. */
sealed interface RukamoriHomeUi {
    data object Loading : RukamoriHomeUi

    /** The feed loaded with nothing to show. Retry calls [RukamoriHomeActions.refresh]. */
    data object Empty : RukamoriHomeUi

    /** The feed failed. [detail] is the owner's message; null shows the generic line. */
    data class Failed(val detail: String? = null) : RukamoriHomeUi

    data class Ready(val content: RukamoriHomeContent) : RukamoriHomeUi
}

/**
 * Everything the Ready page draws, in baseline order: chips, quick picks, speed dial, keep listening, account
 * playlists, forgotten favourites, similar decks, then the remote shelves and the loading-more spinner.
 * Each list is in the owner's order; the variant only removes duplicate refs, as the baseline does.
 */
data class RukamoriHomeContent(
    val chips: List<HomeVariantChip> = emptyList(),
    val selectedChipKey: String? = null,
    val showCategoryChips: Boolean = true,
    val showTonalBackdrop: Boolean = true,
    val quickPicks: HomeVariantQuickPicks = HomeVariantQuickPicks(),
    val quickPickLayout: HomeVariantQuickPickLayout = HomeVariantQuickPickLayout.CARD,
    val speedDial: List<ContentOccurrence> = emptyList(),
    val keepListening: List<ContentOccurrence> = emptyList(),
    val accountName: String = "",
    val accountImageUrl: String? = null,
    val accountPlaylists: List<ContentOccurrence> = emptyList(),
    val forgottenFavorites: List<ContentOccurrence> = emptyList(),
    val similarDecks: List<HomeVariantDeck> = emptyList(),
    val sections: List<HomeVariantSection> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** True while the owner has a continuation; the variant asks for more as the user nears the end. */
    val canLoadMore: Boolean = false,
)

/**
 * The owner's answers to the Rukamori page. [play] receives every playback intent (see [HomeVariantPlay]) and the
 * owner builds the real queue. [open] navigates by ref. [openSection] leads the remote header to its browse or mood
 * page. [menu] opens the item's sheet on long press or the more button.
 */
class RukamoriHomeActions(
    val play: (HomeVariantPlay) -> Unit,
    val open: (ContentRef) -> Unit,
    val openAccount: () -> Unit,
    val openSection: (HomeVariantSection) -> Unit,
    val menu: (ContentOccurrence) -> Unit,
    val selectChip: (HomeVariantChip) -> Unit,
    val loadMore: () -> Unit,
    val refresh: () -> Unit,
)

private val RukamoriHomeMaxWidth = 1_200.dp
private val RukamoriSectionSpacing = 18.dp
internal val HomeVariantThumbnailSize = 56.dp
internal val HomeVariantThumbnailCorner = 10.dp
private const val DiscoveryDeckMaxItems = 4
private const val DiscoveryDeckColumns = 2

/**
 * The Rukamori home page. [artwork] draws content artwork and [image] draws the account avatar and remote section
 * thumbnails; both are host slots. The page is laid out at [LocalShellContentPadding] unless [contentPadding] says
 * otherwise, and [listState] stays hoisted so scroll survives look changes. [thumbnailAspectRatio] gives each shelf and
 * similar-to deck card its frame ratio; the default keeps every card square. The hero carousel and list rows never use it.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun RukamoriHomeVariant(
    state: RukamoriHomeUi,
    actions: RukamoriHomeActions,
    artwork: ContentArtwork,
    nowPlaying: HomeVariantNowPlaying,
    image: HomeVariantImage,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    listState: LazyListState = rememberLazyListState(),
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio = { 1f },
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            RukamoriHomeUi.Loading ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = true,
                    icon = null,
                    message = null,
                    onRetry = null,
                )

            RukamoriHomeUi.Empty ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = false,
                    icon = Res.drawable.m4_icon_music_note,
                    message = stringResource(Res.string.m4hv_no_results),
                    onRetry = actions.refresh,
                )

            is RukamoriHomeUi.Failed ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = false,
                    icon = Res.drawable.m4_icon_info,
                    message = state.detail ?: stringResource(Res.string.m4hv_home_failed),
                    onRetry = actions.refresh,
                )

            is RukamoriHomeUi.Ready ->
                RukamoriHomeFeed(
                    content = state.content,
                    actions = actions,
                    artwork = artwork,
                    nowPlaying = nowPlaying,
                    image = image,
                    contentPadding = contentPadding,
                    listState = listState,
                    thumbnailAspectRatio = thumbnailAspectRatio,
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun RukamoriHomeFeed(
    content: RukamoriHomeContent,
    actions: RukamoriHomeActions,
    artwork: ContentArtwork,
    nowPlaying: HomeVariantNowPlaying,
    image: HomeVariantImage,
    contentPadding: PaddingValues,
    listState: LazyListState,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
) {
    val tonalStart = MaterialTheme.colorScheme.primaryContainer
    val tonalMiddle = MaterialTheme.colorScheme.secondaryContainer
    val keepListeningSongs =
        remember(content.keepListening) { content.keepListening.filter { it.item.ref.kind == ContentKind.SONG } }
    val forgottenFavorites = remember(content.forgottenFavorites) { content.forgottenFavorites.distinctBy { it.item.ref } }
    val quickPicksTitle = stringResource(Res.string.m4hv_quick_picks)
    val keepListeningTitle = stringResource(Res.string.m4hv_keep_listening)
    val forgottenTitle = stringResource(Res.string.m4hv_forgotten_favorites)
    val speedDialTitle = stringResource(Res.string.m4hv_speed_dial)

    // Near the end of the list, ask the owner for the next page once. snapshotFlow only emits on change, so one
    // request fires per approach rather than per frame.
    LaunchedEffect(content.canLoadMore) {
        if (!content.canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex != null && lastVisibleIndex >= layoutInfo.totalItemsCount - 3
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) actions.loadMore()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (content.showTonalBackdrop) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(430.dp)
                        .align(Alignment.TopCenter)
                        .drawWithCache {
                            // Upstream rukamori gradient strengths.
                            val brush =
                                Brush.verticalGradient(
                                    0f to tonalStart.copy(alpha = 0.30f),
                                    0.42f to tonalMiddle.copy(alpha = 0.14f),
                                    1f to Color.Transparent,
                                )
                            onDrawBehind { drawRect(brush) }
                        },
            )
        }

        PullToRefreshBox(
            isRefreshing = content.isRefreshing,
            onRefresh = actions.refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding = contentPadding,
                modifier =
                    Modifier
                        .widthIn(max = RukamoriHomeMaxWidth)
                        .fillMaxWidth()
                        .align(Alignment.TopCenter),
            ) {
                if (content.showCategoryChips) {
                    item(key = "home_category_chips", contentType = "category_chips") {
                        HomeVariantChipRow(
                            chips = content.chips,
                            selectedKey = content.selectedChipKey,
                            onSelect = actions.selectChip,
                        )
                    }
                }

                val remoteQuickPicks = content.quickPicks.remote?.takeIf { it.items.isNotEmpty() }
                if (remoteQuickPicks != null) {
                    rukamoriSpacer("remote_quick_picks")
                    item(key = "home_remote_quick_picks_header", contentType = "section_header") {
                        HomeFeedSectionHeader(
                            title = quickPicksTitle,
                            leadingIcon = { HomeVariantLeadingIcon(Res.drawable.m4hv_icon_discover_tune) },
                        )
                    }
                    item(key = "home_remote_quick_picks", contentType = "quick_picks") {
                        RukamoriQuickPicks(
                            items = remoteQuickPicks.items,
                            layout = content.quickPickLayout,
                            nowPlaying = nowPlaying,
                            artwork = artwork,
                            onPlay = { occurrence ->
                                actions.play(HomeVariantPlay.QuickPick(occurrence, remoteQuickPicks.key, content.quickPickLayout))
                            },
                            onMenu = actions.menu,
                        )
                    }
                } else if (content.quickPicks.localVisible && content.quickPicks.local.isNotEmpty()) {
                    rukamoriSpacer("quick_picks")
                    item(key = "home_quick_picks_header", contentType = "section_header") {
                        HomeFeedSectionHeader(
                            title = quickPicksTitle,
                            leadingIcon = {
                                HomeVariantLeadingIcon(
                                    if (content.quickPicks.localFromHistory) {
                                        Res.drawable.m4_icon_history
                                    } else {
                                        Res.drawable.m4hv_icon_discover_tune
                                    },
                                )
                            },
                        )
                    }
                    item(key = "home_quick_picks", contentType = "quick_picks") {
                        RukamoriQuickPicks(
                            items = content.quickPicks.local,
                            layout = content.quickPickLayout,
                            nowPlaying = nowPlaying,
                            artwork = artwork,
                            onPlay = { occurrence ->
                                actions.play(HomeVariantPlay.QuickPick(occurrence, null, content.quickPickLayout))
                            },
                            onMenu = actions.menu,
                        )
                    }
                }

                if (content.speedDial.isNotEmpty()) {
                    rukamoriSpacer("speed_dial")
                    item(key = "home_speed_dial_header", contentType = "section_header") {
                        HomeFeedSectionHeader(
                            title = speedDialTitle,
                            leadingIcon = { HomeVariantLeadingIcon(Res.drawable.m4hv_icon_bolt) },
                        )
                    }
                    item(key = "home_speed_dial", contentType = "speed_dial") {
                        RukamoriSpeedDial(
                            items = content.speedDial,
                            nowPlaying = nowPlaying,
                            artwork = artwork,
                            shelfTitle = speedDialTitle,
                            actions = actions,
                        )
                    }
                }

                if (content.keepListening.isNotEmpty()) {
                    rukamoriSpacer("keep_listening")
                    item(key = "home_keep_listening_header", contentType = "section_header") {
                        HomeFeedSectionHeader(title = keepListeningTitle)
                    }
                    item(key = "home_keep_listening", contentType = "media_shelf") {
                        RukamoriShelfRow(
                            items = content.keepListening,
                            nowPlaying = nowPlaying,
                            artwork = artwork,
                            thumbnailAspectRatio = thumbnailAspectRatio,
                            onTap = { occurrence, _ ->
                                val ref = occurrence.item.ref
                                when (ref.kind) {
                                    // Songs queue the keep-listening songs in order from the tapped one.
                                    ContentKind.SONG -> {
                                        val start = keepListeningSongs.indexOfFirst { it.item.ref == ref }
                                        if (start >= 0) {
                                            actions.play(
                                                HomeVariantPlay.Shelf(
                                                    title = keepListeningTitle,
                                                    occurrences = keepListeningSongs,
                                                    startIndex = start,
                                                    toggleIfActive = true,
                                                ),
                                            )
                                        }
                                    }
                                    // Baseline: local playlist cards on the shelf do not navigate.
                                    ContentKind.PLAYLIST -> Unit
                                    else -> actions.open(ref)
                                }
                            },
                            onMenu = actions.menu,
                        )
                    }
                }

                if (content.accountPlaylists.isNotEmpty()) {
                    rukamoriSpacer("account_playlists")
                    item(key = "home_account_playlists", contentType = "media_shelf") {
                        Column {
                            HomeFeedSectionHeader(
                                title = content.accountName.ifBlank { stringResource(Res.string.m4hv_account) },
                                subtitle = stringResource(Res.string.m4hv_your_youtube_playlists),
                                thumbnail = { RukamoriAccountThumbnail(content.accountImageUrl, image) },
                                onClick = actions.openAccount,
                            )
                            RukamoriShelfRow(
                                items = content.accountPlaylists,
                                nowPlaying = nowPlaying,
                                artwork = artwork,
                                thumbnailAspectRatio = thumbnailAspectRatio,
                                onTap = { occurrence, _ -> actions.open(occurrence.item.ref) },
                                onMenu = actions.menu,
                            )
                        }
                    }
                }

                if (forgottenFavorites.isNotEmpty()) {
                    rukamoriSpacer("forgotten_favorites")
                    item(key = "home_forgotten_favorites_header", contentType = "section_header") {
                        HomeFeedSectionHeader(title = forgottenTitle)
                    }
                    item(key = "home_forgotten_favorites", contentType = "song_shelf") {
                        RukamoriShelfRow(
                            items = forgottenFavorites,
                            nowPlaying = nowPlaying,
                            artwork = artwork,
                            thumbnailAspectRatio = thumbnailAspectRatio,
                            onTap = { occurrence, index ->
                                // Queues the whole shelf from the tapped song, so Next walks it in order.
                                if (occurrence.item.ref.kind == ContentKind.SONG) {
                                    actions.play(
                                        HomeVariantPlay.Shelf(
                                            title = forgottenTitle,
                                            occurrences = forgottenFavorites,
                                            startIndex = index,
                                            toggleIfActive = true,
                                        ),
                                    )
                                }
                            },
                            onMenu = actions.menu,
                        )
                    }
                }

                // Upstream renders every similar recommendation as one deck rail rather than a header and shelf for
                // each one; the deck carries its own "Similar to" header.
                if (content.similarDecks.isNotEmpty()) {
                    rukamoriSpacer("similar_recommendations")
                    item(key = "home_similar_recommendations", contentType = "discovery_decks") {
                        RukamoriDiscoveryDecks(
                            decks = content.similarDecks,
                            nowPlaying = nowPlaying,
                            artwork = artwork,
                            thumbnailAspectRatio = thumbnailAspectRatio,
                            actions = actions,
                        )
                    }
                }

                content.sections.forEachIndexed { index, section ->
                    val sectionKey = "${section.key}_$index"
                    val shelfTitle = section.title.takeIf { it.isNotBlank() } ?: quickPicksTitle
                    rukamoriSpacer("remote_$sectionKey")
                    item(key = "home_remote_header_$sectionKey", contentType = "section_header") {
                        HomeFeedSectionHeader(
                            title = section.title,
                            subtitle = section.label,
                            leadingIcon = { HomeVariantLeadingIcon(rukamoriSectionIcon(section.title)) },
                            thumbnail =
                                if (section.thumbnailUrl != null) {
                                    { RukamoriRoundThumbnail(section.thumbnailUrl, image) }
                                } else {
                                    null
                                },
                            onClick = { actions.openSection(section) },
                        )
                    }
                    item(key = "home_remote_$sectionKey", contentType = "media_shelf") {
                        RukamoriShelfRow(
                            items = section.items,
                            nowPlaying = nowPlaying,
                            artwork = artwork,
                            thumbnailAspectRatio = thumbnailAspectRatio,
                            onTap = { occurrence, _ ->
                                val ref = occurrence.item.ref
                                when (ref.kind) {
                                    // Remote shelves do not toggle: a tap restarts the shelf from the tapped song.
                                    ContentKind.SONG -> {
                                        val songs = section.items.filter { it.item.ref.kind == ContentKind.SONG }
                                        val start = songs.indexOfFirst { it.item.ref == ref }
                                        if (start >= 0) {
                                            actions.play(
                                                HomeVariantPlay.Shelf(
                                                    title = shelfTitle,
                                                    occurrences = songs,
                                                    startIndex = start,
                                                    toggleIfActive = false,
                                                ),
                                            )
                                        }
                                    }
                                    ContentKind.EPISODE ->
                                        actions.play(HomeVariantPlay.Single(episodeQueueTitle(occurrence), occurrence))
                                    else -> actions.open(ref)
                                }
                            },
                            onMenu = actions.menu,
                        )
                    }
                }

                if (content.isLoadingMore) {
                    item(key = "home_loading_more", contentType = "loading") {
                        HomeVariantLoadingMore()
                    }
                }
            }
        }
    }
}

/** Spacing before each section, as the baseline rukamoriSectionSpacer. */
private fun androidx.compose.foundation.lazy.LazyListScope.rukamoriSpacer(key: String) {
    item(key = "home_section_spacer_$key", contentType = "section_spacer") {
        Spacer(Modifier.height(RukamoriSectionSpacing))
    }
}

/** Episode queue title: the podcast name when the owner supplies it (ContentItem.album), else the episode title. */
internal fun episodeQueueTitle(occurrence: ContentOccurrence): String =
    occurrence.item.album?.takeIf { it.isNotBlank() } ?: occurrence.item.title

/** Keyword-driven glyph for a remote shelf title, matching the baseline section icons. */
internal fun rukamoriSectionIcon(title: String): DrawableResource =
    when {
        title.contains("Live performance", ignoreCase = true) -> Res.drawable.m4_icon_mic
        title.contains("Quick pick", ignoreCase = true) -> Res.drawable.m4hv_icon_discover_tune
        title.contains("Fresh", ignoreCase = true) -> Res.drawable.m4hv_icon_fire
        title.contains("Old", ignoreCase = true) ||
            title.contains("favourite", ignoreCase = true) ||
            title.contains("forgotten", ignoreCase = true) -> Res.drawable.m4_icon_cached
        title.contains("New release", ignoreCase = true) -> Res.drawable.m4hv_icon_new_release
        title.contains("Trending", ignoreCase = true) -> Res.drawable.m4hv_icon_trending_up
        else -> Res.drawable.m4hv_icon_auto_awesome
    }

/** A row of shelf cards. [onTap] receives the occurrence and its index in [items]; long press opens the sheet. */
@Composable
internal fun RukamoriShelfRow(
    items: List<ContentOccurrence>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    onTap: (ContentOccurrence, Int) -> Unit,
    onMenu: (ContentOccurrence) -> Unit,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = HomeFeedGutter),
        horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
        modifier = modifier.fillMaxWidth(),
    ) {
        itemsIndexed(
            items = items,
            key = { _, occurrence -> occurrence.occurrenceId },
            contentType = { _, _ -> "shelf_card" },
        ) { index, occurrence ->
            HomeFeedShelfCard(
                item = occurrence.item,
                artwork = artwork,
                thumbnailAspectRatio = thumbnailAspectRatio.frameRatioFor(occurrence.item),
                onClick = { onTap(occurrence, index) },
                onLongClick =
                    if (occurrence.item.ref.kind.hasHomeMenu()) {
                        { onMenu(occurrence) }
                    } else {
                        null
                    },
                isActive = nowPlaying.isActive(occurrence.item.ref),
                isPlaying = nowPlaying.isPlaying,
            )
        }
    }
}

/** The account avatar, or the person glyph when the account has no picture. */
@Composable
private fun RukamoriAccountThumbnail(imageUrl: String?, image: HomeVariantImage) {
    if (imageUrl != null) {
        RukamoriRoundThumbnail(imageUrl, image)
    } else {
        Icon(
            painter = painterResource(Res.drawable.m4hv_icon_person),
            contentDescription = null,
            modifier = Modifier.size(HomeVariantThumbnailSize),
        )
    }
}

/** A remote thumbnail drawn through the host image slot at the baseline 56dp, rounded 10dp. */
@Composable
internal fun RukamoriRoundThumbnail(url: String, image: HomeVariantImage) {
    image(
        url,
        Modifier
            .size(HomeVariantThumbnailSize)
            .clip(RoundedCornerShape(HomeVariantThumbnailCorner)),
    )
}

/** The similar-to deck, a rail of two-column decks. Each deck shows its own header and up to four items. */
@Composable
private fun RukamoriDiscoveryDecks(
    decks: List<HomeVariantDeck>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    actions: RukamoriHomeActions,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
    modifier: Modifier = Modifier,
) {
    val distinctDecks = remember(decks) { decks.distinctBy { it.source.ref } }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // A deck is two shelf cards wide plus its own gutter, so the cards land on the shelf rhythm and the deck
        // only shrinks when the screen is narrower than that.
        val deckWidth =
            (HomeShelfCardWidth * DiscoveryDeckColumns + HomeShelfCardSpacing + HomeFeedGutter * 2)
                .coerceAtMost(maxWidth - HomeFeedGutter * 2)
        LazyRow(
            contentPadding = PaddingValues(horizontal = HomeFeedGutter),
            horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
        ) {
            items(
                items = distinctDecks,
                key = { deck -> deck.key },
                contentType = { "similar_discovery_deck" },
            ) { deck ->
                RukamoriDiscoveryDeck(
                    deck = deck,
                    nowPlaying = nowPlaying,
                    artwork = artwork,
                    actions = actions,
                    thumbnailAspectRatio = thumbnailAspectRatio,
                    modifier = Modifier.width(deckWidth),
                )
            }
        }
    }
}

@Composable
private fun RukamoriDiscoveryDeck(
    deck: HomeVariantDeck,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    actions: RukamoriHomeActions,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
    modifier: Modifier = Modifier,
) {
    val deckItems = remember(deck.items) { deck.items.distinctBy { it.item.ref }.take(DiscoveryDeckMaxItems) }
    val deckRows = remember(deckItems) { deckItems.chunked(DiscoveryDeckColumns) }
    val deckSongs = remember(deckItems) { deckItems.filter { it.item.ref.kind == ContentKind.SONG } }
    val openRef = deck.openRef

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            HomeFeedSectionHeader(
                title = deck.source.title,
                subtitle = stringResource(Res.string.m4hv_similar_to),
                thumbnail = { RukamoriDeckSourceThumbnail(deck.source, artwork) },
                onClick = if (openRef != null) { { actions.open(openRef) } } else null,
            )
            deckRows.forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = HomeFeedGutter),
                ) {
                    rowItems.forEach { occurrence ->
                        val ref = occurrence.item.ref
                        HomeFeedShelfCard(
                            item = occurrence.item,
                            artwork = artwork,
                            thumbnailAspectRatio = thumbnailAspectRatio.frameRatioFor(occurrence.item),
                            onClick = {
                                when (ref.kind) {
                                    // Deck songs do not toggle: a tap restarts the deck from the tapped song.
                                    ContentKind.SONG -> {
                                        val start = deckSongs.indexOfFirst { it.item.ref == ref }
                                        if (start >= 0) {
                                            actions.play(
                                                HomeVariantPlay.Shelf(
                                                    title = deck.source.title,
                                                    occurrences = deckSongs,
                                                    startIndex = start,
                                                    toggleIfActive = false,
                                                ),
                                            )
                                        }
                                    }
                                    ContentKind.EPISODE ->
                                        actions.play(HomeVariantPlay.Single(episodeQueueTitle(occurrence), occurrence))
                                    else -> actions.open(ref)
                                }
                            },
                            onLongClick =
                                if (ref.kind.hasHomeMenu()) {
                                    { actions.menu(occurrence) }
                                } else {
                                    null
                                },
                            isActive = nowPlaying.isActive(ref),
                            isPlaying = nowPlaying.isPlaying,
                        )
                    }
                    repeat(DiscoveryDeckColumns - rowItems.size) {
                        Spacer(Modifier.width(HomeShelfCardWidth))
                    }
                }
            }
        }
    }
}

/** A deck header's artwork: the source item, a circle for artists and a rounded square otherwise. */
@Composable
private fun RukamoriDeckSourceThumbnail(source: ContentItem, artwork: ContentArtwork) {
    val shape: Shape =
        if (source.ref.kind == ContentKind.ARTIST) {
            CircleShape
        } else {
            RoundedCornerShape(HomeVariantThumbnailCorner)
        }
    artwork(source, Modifier.size(HomeVariantThumbnailSize).clip(shape))
}
