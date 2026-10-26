package moe.rukamori.archivetune.shared.ui.home.variants
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.home.HomePullRefreshLine
import moe.rukamori.archivetune.shared.ui.home.HomeShelfBottomSpacing
import moe.rukamori.archivetune.shared.ui.home.HomeShelfCardSpacing
import moe.rukamori.archivetune.shared.ui.home.homeFeedMoreSkeleton
import moe.rukamori.archivetune.shared.ui.home.homeHeroCardWidth
import moe.rukamori.archivetune.shared.ui.navigation.LocalShellContentPadding
import moe.rukamori.archivetune.shared.ui.resources.*
import moe.rukamori.archivetune.shared.ui.screens.ContentArtwork
import org.jetbrains.compose.resources.stringResource


/** The BitChord page as the owner delivers it. Each state is complete; the variant never invents a shelf. */
sealed interface BitChordHomeUi {
    data object Loading : BitChordHomeUi

    /** The feed loaded with nothing to show. Retry calls [BitChordHomeActions.refresh]. */
    data object Empty : BitChordHomeUi

    /** The feed failed. [detail] is the owner's message; null shows the generic line. */
    data class Failed(val detail: String? = null) : BitChordHomeUi

    data class Ready(val content: BitChordHomeContent) : BitChordHomeUi
}

/**
 * Everything the Ready BitChord page draws, in baseline order: the recents shelf (only above one recent), jump back in,
 * remote quick picks, keep listening, speed dial, live performances, account playlists, forgotten favourites, similar
 * recommendations, other remote sections, then the loading-more skeleton. [minimal] is the minimal home mode, which hides
 * the account playlists, forgotten favourites, similar recommendations and other remote sections, as the baseline does.
 * Remote sections with no items are not drawn.
 */
data class BitChordHomeContent(
    val recents: List<ContentOccurrence> = emptyList(),
    val heroPicks: List<ContentOccurrence> = emptyList(),
    val remoteQuickPicks: HomeVariantSection? = null,
    val keepListening: List<ContentOccurrence> = emptyList(),
    val speedDial: List<ContentOccurrence> = emptyList(),
    val livePerformances: List<HomeVariantSection> = emptyList(),
    val accountName: String = "",
    val accountPlaylists: List<ContentOccurrence> = emptyList(),
    val forgottenFavorites: List<ContentOccurrence> = emptyList(),
    val similarRecommendations: List<HomeVariantSection> = emptyList(),
    val otherSections: List<HomeVariantSection> = emptyList(),
    val minimal: Boolean = false,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** True while the owner has a continuation; the variant asks for more as the user nears the end. */
    val canLoadMore: Boolean = false,
)

/**
 * The owner's answers to the BitChord page. [play] receives every playback intent and the owner builds the real queue.
 * [open] navigates by ref: albums, artists and playlists, including the account's playlists. [menu] opens the item's
 * sheet on long press; remote shelves offer it for songs only and the local shelves for every kind, as the baseline does.
 */
class BitChordHomeActions(
    val play: (HomeVariantPlay) -> Unit,
    val open: (ContentRef) -> Unit,
    val menu: (ContentOccurrence) -> Unit,
    val loadMore: () -> Unit,
    val refresh: () -> Unit,
)

private val BitChordHomeMaxWidth = 1_200.dp

/**
 * The BitChord home page. [state] chooses between the shared loading, empty and failure panes and the feed. The shell
 * draws the greeting above the page, so the page starts at the recents shelf. BitChord draws no section thumbnails and
 * takes no image slot; [artwork] is the host's artwork slot for content items. [listState] stays hoisted so scroll
 * survives look changes. [thumbnailAspectRatio] gives each shelf card its frame ratio; the default keeps every card
 * square. The jump-back-in hero cards keep their fixed ratio and the recents list keeps its 48dp rows.
 */
@Composable
fun BitChordHomeVariant(
    state: BitChordHomeUi,
    actions: BitChordHomeActions,
    artwork: ContentArtwork,
    nowPlaying: HomeVariantNowPlaying,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = LocalShellContentPadding.current,
    listState: LazyListState = rememberLazyListState(),
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio = { 1f },
) {
    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            BitChordHomeUi.Loading ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = true,
                    icon = null,
                    message = null,
                    onRetry = null,
                )

            BitChordHomeUi.Empty ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = false,
                    icon = Res.drawable.m4_icon_music_note,
                    message = stringResource(Res.string.m4hv_no_results),
                    onRetry = actions.refresh,
                )

            is BitChordHomeUi.Failed ->
                HomeVariantStatePane(
                    contentPadding = contentPadding,
                    showLoading = false,
                    icon = Res.drawable.m4_icon_info,
                    message = state.detail ?: stringResource(Res.string.m4hv_home_failed),
                    onRetry = actions.refresh,
                )

            is BitChordHomeUi.Ready ->
                BitChordHomeFeed(
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
private fun BitChordHomeFeed(
    content: BitChordHomeContent,
    actions: BitChordHomeActions,
    artwork: ContentArtwork,
    nowPlaying: HomeVariantNowPlaying,
    contentPadding: PaddingValues,
    listState: LazyListState,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
) {
    val recentsTitle = stringResource(Res.string.m4hv_bitchord_recently_played)
    val jumpBackInTitle = stringResource(Res.string.m4hv_jump_back_in)
    val keepListeningTitle = stringResource(Res.string.m4hv_keep_listening)
    val speedDialTitle = stringResource(Res.string.m4hv_speed_dial)
    val accountPlaylistsTitle = stringResource(Res.string.m4hv_your_youtube_playlists)
    val forgottenTitle = stringResource(Res.string.m4hv_forgotten_favorites)
    // A shelf queue's start index is taken from the same de-duplicated list the owner receives, so the index lines up.
    val distinctRecents = remember(content.recents) { content.recents.distinctBy { it.item.ref } }
    val distinctHeroPicks = remember(content.heroPicks) { content.heroPicks.distinctBy { it.item.ref } }
    val distinctForgotten = remember(content.forgottenFavorites) { content.forgottenFavorites.distinctBy { it.item.ref } }
    val remoteQuickPicks = content.remoteQuickPicks?.takeIf { it.items.isNotEmpty() }
    val liveSections = remember(content.livePerformances) { content.livePerformances.filter { it.items.isNotEmpty() } }
    val similarSections =
        remember(content.similarRecommendations) { content.similarRecommendations.filter { it.items.isNotEmpty() } }
    val otherSections = remember(content.otherSections) { content.otherSections.filter { it.items.isNotEmpty() } }
    val pullState = rememberPullToRefreshState()
    val currentActions by rememberUpdatedState(actions)

    // Near the end of the list, ask the owner for the next page once. snapshotFlow only emits on change, so one
    // request fires per approach rather than per frame.
    LaunchedEffect(content.canLoadMore) {
        if (!content.canLoadMore) return@LaunchedEffect
        snapshotFlow {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index
            lastVisibleIndex != null && lastVisibleIndex >= layoutInfo.totalItemsCount - 3
        }.collect { shouldLoadMore ->
            if (shouldLoadMore) currentActions.loadMore()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = content.isRefreshing,
            onRefresh = actions.refresh,
            state = pullState,
            indicator = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val availableWidth = maxWidth
                LazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    modifier =
                        Modifier
                            .widthIn(max = BitChordHomeMaxWidth)
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                ) {
                    if (content.recents.size > 1) {
                        item(key = "bitchord_recents", contentType = "recents") {
                            BitChordRecentsShelf(
                                title = recentsTitle,
                                items = content.recents,
                                nowPlaying = nowPlaying,
                                artwork = artwork,
                                availableWidth = availableWidth,
                                onPlay = { _, index ->
                                    actions.play(
                                        HomeVariantPlay.Shelf(recentsTitle, distinctRecents, index, toggleIfActive = false),
                                    )
                                },
                                onMenu = actions.menu,
                            )
                        }
                    }

                    if (distinctHeroPicks.isNotEmpty()) {
                        item(key = "bitchord_jump_back_in", contentType = "jump_back_in") {
                            Column {
                                BitChordSectionHeader(title = jumpBackInTitle)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                                    horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                                ) {
                                    itemsIndexed(
                                        distinctHeroPicks,
                                        key = { _, occurrence -> occurrence.occurrenceId },
                                    ) { index, occurrence ->
                                        BitChordHeroCard(
                                            item = occurrence.item,
                                            artwork = artwork,
                                            isCurrent = nowPlaying.isActive(occurrence.item.ref),
                                            isPlaying = nowPlaying.isPlaying,
                                            onClick = {
                                                actions.play(
                                                    HomeVariantPlay.Shelf(
                                                        jumpBackInTitle,
                                                        distinctHeroPicks,
                                                        index,
                                                        toggleIfActive = false,
                                                    ),
                                                )
                                            },
                                            onLongClick = { actions.menu(occurrence) },
                                            modifier = Modifier.width(homeHeroCardWidth(availableWidth)),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(HomeShelfBottomSpacing))
                            }
                        }
                    }

                    if (remoteQuickPicks != null) {
                        item(key = "bitchord_remote_quick_picks", contentType = "media_shelf") {
                            BitChordRemoteShelf(
                                section = remoteQuickPicks,
                                nowPlaying = nowPlaying,
                                artwork = artwork,
                                thumbnailAspectRatio = thumbnailAspectRatio,
                                actions = actions,
                            )
                        }
                    }

                    if (content.keepListening.isNotEmpty()) {
                        item(key = "bitchord_keep_listening", contentType = "media_shelf") {
                            BitChordLocalShelf(
                                title = keepListeningTitle,
                                occurrences = content.keepListening,
                                nowPlaying = nowPlaying,
                                artwork = artwork,
                                thumbnailAspectRatio = thumbnailAspectRatio,
                                actions = actions,
                            )
                        }
                    }

                    if (content.speedDial.isNotEmpty()) {
                        item(key = "bitchord_speed_dial", contentType = "media_shelf") {
                            BitChordLocalShelf(
                                title = speedDialTitle,
                                occurrences = content.speedDial,
                                nowPlaying = nowPlaying,
                                artwork = artwork,
                                thumbnailAspectRatio = thumbnailAspectRatio,
                                actions = actions,
                            )
                        }
                    }

                    liveSections.forEachIndexed { index, section ->
                        item(key = "bitchord_live_${index}_${section.key}", contentType = "media_shelf") {
                            BitChordRemoteShelf(
                                section = section,
                                nowPlaying = nowPlaying,
                                artwork = artwork,
                                thumbnailAspectRatio = thumbnailAspectRatio,
                                actions = actions,
                            )
                        }
                    }

                    if (!content.minimal && content.accountPlaylists.isNotEmpty()) {
                        item(key = "bitchord_account_playlists", contentType = "media_shelf") {
                            Column {
                                BitChordSectionHeader(
                                    title = accountPlaylistsTitle,
                                    subtitle = content.accountName.takeIf { it.isNotBlank() },
                                )
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                                    horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                                ) {
                                    items(content.accountPlaylists, key = { it.occurrenceId }) { occurrence ->
                                        BitChordShelfCard(
                                            item = occurrence.item,
                                            artwork = artwork,
                                            isCurrent = nowPlaying.isActive(occurrence.item.ref),
                                            isPlaying = nowPlaying.isPlaying,
                                            onClick = { actions.open(occurrence.item.ref) },
                                            thumbnailAspectRatio = thumbnailAspectRatio.frameRatioFor(occurrence.item),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(HomeShelfBottomSpacing))
                            }
                        }
                    }

                    if (!content.minimal && distinctForgotten.isNotEmpty()) {
                        item(key = "bitchord_forgotten_favorites", contentType = "song_shelf") {
                            Column {
                                BitChordSectionHeader(title = forgottenTitle)
                                LazyRow(
                                    contentPadding = PaddingValues(horizontal = BitChordPageGutter),
                                    horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
                                ) {
                                    itemsIndexed(
                                        distinctForgotten,
                                        key = { _, occurrence -> occurrence.occurrenceId },
                                    ) { index, occurrence ->
                                        BitChordShelfCard(
                                            item = occurrence.item,
                                            artwork = artwork,
                                            isCurrent = nowPlaying.isActive(occurrence.item.ref),
                                            isPlaying = nowPlaying.isPlaying,
                                            onClick = {
                                                actions.play(
                                                    HomeVariantPlay.Shelf(
                                                        forgottenTitle,
                                                        distinctForgotten,
                                                        index,
                                                        toggleIfActive = false,
                                                    ),
                                                )
                                            },
                                            onLongClick = { actions.menu(occurrence) },
                                            thumbnailAspectRatio = thumbnailAspectRatio.frameRatioFor(occurrence.item),
                                        )
                                    }
                                }
                                Spacer(Modifier.height(HomeShelfBottomSpacing))
                            }
                        }
                    }

                    if (!content.minimal) {
                        similarSections.forEachIndexed { index, section ->
                            item(key = "bitchord_similar_${index}_${section.key}", contentType = "media_shelf") {
                                BitChordRemoteShelf(
                                    section = section,
                                    nowPlaying = nowPlaying,
                                    artwork = artwork,
                                    thumbnailAspectRatio = thumbnailAspectRatio,
                                    actions = actions,
                                )
                            }
                        }
                        otherSections.forEachIndexed { index, section ->
                            item(key = "bitchord_remote_${index}_${section.key}", contentType = "media_shelf") {
                                BitChordRemoteShelf(
                                    section = section,
                                    nowPlaying = nowPlaying,
                                    artwork = artwork,
                                    thumbnailAspectRatio = thumbnailAspectRatio,
                                    actions = actions,
                                )
                            }
                        }
                    }

                    if (content.isLoadingMore) {
                        homeFeedMoreSkeleton()
                    }
                }
            }
        }
        HomePullRefreshLine(
            refreshing = content.isRefreshing,
            distanceFraction = { pullState.distanceFraction },
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = contentPadding.calculateTopPadding()),
        )
    }
}

/**
 * A remote shelf from YouTube. A song starts its own watch queue, and the owner toggles when it already plays; an album,
 * artist or playlist navigates by ref. Only songs offer the sheet on long press.
 */
@Composable
private fun BitChordRemoteShelf(
    section: HomeVariantSection,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    actions: BitChordHomeActions,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
) {
    Column {
        BitChordSectionHeader(title = section.title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = BitChordPageGutter),
            horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
        ) {
            items(section.items, key = { it.occurrenceId }) { occurrence ->
                val ref = occurrence.item.ref
                val isSong = ref.kind == ContentKind.SONG
                BitChordShelfCard(
                    item = occurrence.item,
                    artwork = artwork,
                    isCurrent = nowPlaying.isActive(ref),
                    isPlaying = nowPlaying.isPlaying,
                    onClick = {
                        if (isSong) actions.play(HomeVariantPlay.Watch(occurrence)) else actions.open(ref)
                    },
                    onLongClick = if (isSong) ({ actions.menu(occurrence) }) else null,
                    thumbnailAspectRatio = thumbnailAspectRatio.frameRatioFor(occurrence.item),
                )
            }
        }
        Spacer(Modifier.height(HomeShelfBottomSpacing))
    }
}

/**
 * A local shelf (keep listening and speed dial). A song queues the shelf's songs from the tapped one and toggles when it
 * already plays; an album, artist or playlist navigates by ref. Every kind offers its sheet on long press.
 */
@Composable
private fun BitChordLocalShelf(
    title: String,
    occurrences: List<ContentOccurrence>,
    nowPlaying: HomeVariantNowPlaying,
    artwork: ContentArtwork,
    actions: BitChordHomeActions,
    thumbnailAspectRatio: HomeVariantThumbnailAspectRatio,
) {
    val songs = remember(occurrences) { occurrences.filter { it.item.ref.kind == ContentKind.SONG } }
    Column {
        BitChordSectionHeader(title = title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = BitChordPageGutter),
            horizontalArrangement = Arrangement.spacedBy(HomeShelfCardSpacing),
        ) {
            items(occurrences, key = { it.occurrenceId }) { occurrence ->
                val ref = occurrence.item.ref
                val isSong = ref.kind == ContentKind.SONG
                BitChordShelfCard(
                    item = occurrence.item,
                    artwork = artwork,
                    isCurrent = nowPlaying.isActive(ref),
                    isPlaying = nowPlaying.isPlaying,
                    onClick = {
                        if (isSong) {
                            val start = songs.indexOfFirst { it.occurrenceId == occurrence.occurrenceId }
                            actions.play(HomeVariantPlay.Shelf(title, songs, start, toggleIfActive = true))
                        } else {
                            actions.open(ref)
                        }
                    },
                    onLongClick = if (ref.kind.hasHomeMenu()) ({ actions.menu(occurrence) }) else null,
                    thumbnailAspectRatio = thumbnailAspectRatio.frameRatioFor(occurrence.item),
                )
            }
        }
        Spacer(Modifier.height(HomeShelfBottomSpacing))
    }
}
