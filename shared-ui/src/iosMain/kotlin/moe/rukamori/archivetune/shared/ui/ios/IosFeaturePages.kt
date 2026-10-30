package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountRequired
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentNews
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentSurface
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveGraphPage
import moe.rukamori.archivetune.shared.ui.screens.AccountContentType
import moe.rukamori.archivetune.shared.ui.screens.AccountUi
import moe.rukamori.archivetune.shared.ui.screens.NewsArticleUi
import moe.rukamori.archivetune.shared.ui.screens.NewsUi
import moe.rukamori.archivetune.shared.ui.screens.PagedContentState
import moe.rukamori.archivetune.shared.ui.screens.PodcastBrowseUi
import moe.rukamori.archivetune.shared.ui.screens.PodcastUi
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoActions
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoSongUi
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoUi
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.TrackInfoUi

/**
 * The news feed the Android baseline reads is inactive, so the screen reports `m4_news_unavailable` instead of an
 * empty feed presented as a real one, and `markRead` never fires. This is the same state the desktop host shows.
 */
internal fun iosNewsPage(): ArchiveGraphPage =
    ArchiveGraphPage.News(state = NewsUi(items = emptyList(), available = false), query = {}, refresh = {}, markRead = {})

internal fun iosArticlePage(newsId: String): ArchiveGraphPage =
    ArchiveGraphPage.Article(
        state = NewsArticleUi(
            item = ContentNews(
                id = newsId, title = "", description = "", imageUrls = emptyList(), important = false, author = "",
                timestamp = 0L,
            ),
            markdown = null,
            available = false,
        ),
        retry = {},
    )

/**
 * No podcast browse transport is connected on this host, so the page mounts as an initialized, failed page with no
 * subscription operation rather than an empty catalogue presented as real.
 */
internal fun iosPodcastsPage(): ArchiveGraphPage =
    ArchiveGraphPage.Podcasts(
        state = PodcastBrowseUi(page = PagedContentState(initialized = true, failed = true), subscriptionsAvailable = false),
        query = {},
        subscriptionsOnly = {},
        subscribe = null,
        retry = {},
        loadMore = {},
    )

internal fun iosPodcastPage(browseId: String): ArchiveGraphPage =
    ArchiveGraphPage.Podcast(
        state = PodcastUi(
            collection = ContentRequest(
                provider = ContentProvider.YOUTUBE,
                surface = ContentSurface.PODCAST,
                parent = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.PODCAST, browseId),
            ),
            header = null,
            description = null,
            episodes = emptyList(),
            page = PagedContentState(initialized = true, failed = true),
            subscription = null,
        ),
        subscribe = null,
        retry = {},
        loadMore = {},
    )

/**
 * The signed-in YouTube library shelves. A session without a YouTube account keeps the host boundary, as on desktop;
 * a failed read leaves the shelves unloaded and reports once.
 */
@Composable
internal fun iosAccountPage(graph: IosSessionGraph, menu: IosItemMenuState): ArchiveGraphPage {
    var selected by remember { mutableStateOf(AccountContentType.PLAYLISTS) }
    var lists by remember { mutableStateOf<Map<AccountContentType, List<ContentItem>>?>(null) }
    var denied by remember { mutableStateOf(false) }
    LaunchedEffect(graph) {
        denied = false
        lists = try {
            graph.youtubeTransport.refreshSession(graph.settings.snapshots.value)
            AccountContentType.entries.associateWith { type -> graph.readAccountSurface(type.toSurface()) }
        } catch (required: ContentAccountRequired) {
            denied = true
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the YouTube account page could not be loaded: ${failure::class.simpleName}")
            null
        }
    }
    if (denied) return ArchiveGraphPage.Host
    val loaded = lists
    return ArchiveGraphPage.Account(
        state = AccountUi(
            selected = selected,
            playlists = loaded?.get(AccountContentType.PLAYLISTS),
            albums = loaded?.get(AccountContentType.ALBUMS),
            artists = loaded?.get(AccountContentType.ARTISTS),
        ),
        select = { selected = it },
        more = { item -> menu.open(ContentOccurrence("account:${item.ref.provider}:${item.ref.id}", item)) },
    )
}

private fun AccountContentType.toSurface(): ContentSurface = when (this) {
    AccountContentType.PLAYLISTS -> ContentSurface.LIBRARY_PLAYLISTS
    AccountContentType.ALBUMS -> ContentSurface.LIBRARY_ALBUMS
    AccountContentType.ARTISTS -> ContentSurface.LIBRARY_ARTISTS
}

/** Every page of one account surface, bounded so a repeating continuation cannot loop the read forever. */
private suspend fun IosSessionGraph.readAccountSurface(surface: ContentSurface): List<ContentItem> {
    val items = mutableListOf<ContentItem>()
    var continuation: String? = null
    repeat(ACCOUNT_PAGE_LIMIT) {
        val page = youtubeContent.page(ContentRequest(provider = ContentProvider.YOUTUBE, surface = surface), continuation)
        items += page.items.map { it.item }
        continuation = page.continuation ?: return items
    }
    return items
}

private const val ACCOUNT_PAGE_LIMIT = 25

/**
 * Track info from the local library. This host keeps no downloaded or local audio file to parse, so the stream format
 * stays null rather than a quality-tier guess, and the screen hides that section.
 */
@Composable
internal fun iosTrackInfoPage(graph: IosSessionGraph, trackId: String): ArchiveGraphPage {
    var state by remember(trackId) { mutableStateOf<TrackInfoUi?>(null) }
    LaunchedEffect(trackId) {
        state = try {
            graph.library.findSong(trackId, null)?.let { item ->
                TrackInfoUi(
                    trackId = trackId,
                    title = item.title,
                    artists = item.artists.joinToString(", ") { it.name }.ifEmpty { item.subtitle },
                    album = item.album,
                    durationMs = item.durationMs,
                    artworkUrl = item.artworkUri,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the track info was not loaded: ${failure::class.simpleName}")
            null
        }
    }
    return ArchiveGraphPage.TrackInfo(
        state = state ?: TrackInfoUi(trackId = trackId),
        artwork = { url, modifier -> IosRemoteArtwork(url, graph.artworkLoader, modifier) },
    )
}

/**
 * Media info from the local library and the queue. Copy writes the general pasteboard and Share opens the system share
 * sheet. No file is parsed and no provider video details load on this host, so those sections stay null.
 */
@Composable
internal fun iosMediaInfoPage(graph: IosSessionGraph, host: IosHost, mediaId: String): ArchiveGraphPage {
    val queueState by graph.queue.state.collectAsState()
    val currentId = queueState.entries.firstOrNull { it.occurrenceId == queueState.currentOccurrenceId }?.item?.ref?.id
    var item by remember(mediaId) { mutableStateOf<ContentItem?>(null) }
    LaunchedEffect(mediaId) {
        item = try {
            graph.library.findSong(mediaId, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            graph.report("the media info was not loaded: ${failure::class.simpleName}")
            null
        }
    }
    val found = item
    return ArchiveGraphPage.MediaInfo(
        state = MediaInfoUi(
            mediaId = mediaId,
            song = found?.let {
                MediaInfoSongUi(
                    title = it.title,
                    artists = it.artists.map { credit -> credit.name },
                    thumbnailUrl = it.artworkUri,
                    durationSeconds = (it.durationMs ?: 0L) / 1_000L,
                )
            },
            isLocal = found?.ref?.provider == ContentProvider.LOCAL.id,
            isLiveTrack = currentId == mediaId,
        ),
        actions = MediaInfoActions(
            onCopy = { text -> copyToIosClipboard(text) },
            onShare = { text -> iosShareText(host, text) },
        ),
        artwork = { url, modifier -> IosRemoteArtwork(url, graph.artworkLoader, modifier) },
    )
}
