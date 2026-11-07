package moe.rukamori.archivetune.shared.data.provider

import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.innertube.models.AlbumItem
import moe.rukamori.archivetune.innertube.models.AlbumReleaseType
import moe.rukamori.archivetune.innertube.models.Artist
import moe.rukamori.archivetune.innertube.models.ArtistItem
import moe.rukamori.archivetune.innertube.models.BrowseEndpoint
import moe.rukamori.archivetune.innertube.models.EpisodeItem
import moe.rukamori.archivetune.innertube.models.PlaylistItem
import moe.rukamori.archivetune.innertube.models.PodcastItem
import moe.rukamori.archivetune.innertube.models.SongItem
import moe.rukamori.archivetune.innertube.models.WatchEndpoint
import moe.rukamori.archivetune.innertube.models.YTItem
import moe.rukamori.archivetune.innertube.pages.ArtistSectionLayout
import moe.rukamori.archivetune.innertube.pages.ChartsPage
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentSection
import moe.rukamori.archivetune.shared.domain.repository.UnsupportedContentOperation

/**
 * Shelf-level YouTube catalogue surfaces for the desktop host: the home feed, artist pages, charts,
 * moods & genres, generic browse shelves, new releases and the account's YouTube history rows. Everything
 * reads through the same retained InnerTube client as [JvmYouTubeBrowseTransport]; a failed request throws
 * and reaches the host, never an empty shelf that looks loaded.
 *
 * The mapping mirrors the Android screens (`HomeScreen`, `ArtistScreen`, `ChartsScreen`,
 * `MoodAndGenresScreen`, `YouTubeBrowseScreen`, `NewReleaseScreen`, `HistoryScreen`). Keep the two in step.
 *
 * The shelf shapes live in commonMain ([YouTubeShelfPage] and friends), so the iOS transport fills the same models
 * from its own InnerTubeX session.
 */

/**
 * The desktop catalogue transport. The core client is an object shared with [JvmYouTubeBrowseTransport], so the session
 * that transport applies (cookie, visitor data, login-for-browse) covers these reads as well.
 */
class JvmYouTubeCatalogueTransport : YouTubeCatalogueTransport {
    private val historyPages = CoreYouTubeHistory()

    /** The home feed: chips on the first page, shelf sections and the continuation for the next batch. */
    override suspend fun home(continuation: String?, params: String?): YouTubeShelfPage {
        val page = YouTube.home(continuation, params).getOrThrow()
        return YouTubeShelfPage(
            shelves = page.sections.mapIndexed { index, section ->
                YouTubeShelf(
                    key = "${section.title}#$index",
                    title = section.title,
                    label = section.label,
                    thumbnail = section.thumbnail,
                    entries = section.items.map { it.toCatalogueEntry() },
                    more = section.endpoint?.toContentSection(),
                    numItemsPerColumn = section.numItemsPerColumn,
                )
            },
            continuation = page.continuation,
            chips =
                page.chips.orEmpty().map { chip ->
                    YouTubeFeedChip(chip.title, chip.endpoint?.params, chip.deselectEndPoint?.params)
                },
        )
    }

    /** The remote artist page: header facts, ordered sections and the watch endpoints the provider gave. */
    override suspend fun artist(browseId: String): YouTubeArtistDetail {
        val page = YouTube.artist(browseId).getOrThrow()
        val shelves =
            page.sections.map { section ->
                YouTubeArtistSection(
                    title = section.title,
                    entries = section.items.map { it.toCatalogueEntry() },
                    more = section.moreEndpoint?.toContentSection(),
                    grid = section.layout == ArtistSectionLayout.GRID,
                )
            }
        // The release shelves double as the releases grid, matching the Android artist page's album list.
        val releases =
            page.sections
                .flatMap { it.items }
                .filterIsInstance<AlbumItem>()
                .distinctBy { it.browseId }
                .map { it.toRelease() }
        val item = page.artist
        return YouTubeArtistDetail(
            artist =
                YouTubeEntry(
                    kind = ContentKind.ARTIST,
                    id = item.id,
                    title = item.title,
                    subtitle = item.subscriberCountText ?: item.monthlyListenerCountText.orEmpty(),
                    artworkUri = item.thumbnail,
                ),
            subscribersText = item.subscriberCountText,
            monthlyListenersText = item.monthlyListenerCountText,
            description = page.description,
            shelves = shelves,
            releases = releases,
            shareLink = item.shareLink,
            play = item.playEndpoint?.toWatchTarget(),
            shuffle = item.shuffleEndpoint?.toWatchTarget(),
            radio = item.radioEndpoint?.toWatchTarget(),
        )
    }

    /** A generic browse page keyed by browse id; the provider returns whole sections, no continuation. */
    suspend fun browseSection(browseId: String, params: String?): YouTubeShelfPage {
        val result = YouTube.browse(browseId, params).getOrThrow()
        return YouTubeShelfPage(
            title = result.title,
            shelves =
                result.items.mapIndexed { index, section ->
                    YouTubeShelf(
                        key = "${section.title ?: "section"}#$index",
                        title = section.title.orEmpty(),
                        entries = section.items.map { it.toCatalogueEntry() },
                        grid = true,
                    )
                },
        )
    }

    /** The charts page: ranked top songs first, then the genre/new-release shelves, paged by continuation. */
    override suspend fun charts(continuation: String?): YouTubeShelfPage {
        val page = YouTube.getChartsPage(continuation).getOrThrow()
        return YouTubeShelfPage(
            shelves =
                page.sections.mapIndexed { index, section ->
                    YouTubeShelf(
                        key = "${section.title}#$index",
                        title = section.title,
                        entries = section.items.map { it.toCatalogueEntry() },
                        numbered = section.chartType == ChartsPage.ChartType.TOP,
                        grid = section.chartType != ChartsPage.ChartType.TOP,
                    )
                },
            continuation = page.continuation,
        )
    }

    /** The explore page: the new-release shelf plus the flat mood/genre tile list the Android screen shows. */
    override suspend fun explore(): YouTubeExplore {
        val page = YouTube.explore().getOrThrow()
        return YouTubeExplore(
            releases = page.newReleaseAlbums.map { it.toRelease() },
            moods =
                page.moodAndGenres.mapNotNull { item ->
                    item.endpoint.toContentSection()?.let { section ->
                        YouTubeMoodGenre(item.title, item.stripeColor, section)
                    }
                },
        )
    }

    /** The new-releases page: every album the endpoint returned, in the provider's order. */
    override suspend fun newReleases(): List<YouTubeRelease> =
        YouTube.newReleaseAlbums().getOrThrow().map { it.toRelease() }

    /** The account's YouTube listen history sections; a signed-out or failed read throws. */
    suspend fun historySections(continuation: String? = null): YouTubeHistoryPage = historyPages.page(continuation)
}

/**
 * The section-paged half of the catalogue: the parts of [JvmYouTubeBrowseTransport.page] that are not the
 * whole-page surfaces. They live as extensions so the two browse mappings stay side by side.
 */
internal suspend fun JvmYouTubeBrowseTransport.catalogueSectionPage(browse: YouTubeBrowse.Section): YouTubeEntryPage {
    val page = YouTube.browse(browse.browseId, browse.params).getOrThrow()
    return YouTubeEntryPage(page.items.flatMap { it.items }.map { it.toCatalogueEntry() })
}

internal suspend fun JvmYouTubeBrowseTransport.catalogueArtistItemsPage(
    browse: YouTubeBrowse.ArtistItems,
    continuation: String?,
): YouTubeEntryPage =
    if (continuation == null) {
        val page = YouTube.artistItems(BrowseEndpoint(browseId = browse.browseId, params = browse.params)).getOrThrow()
        YouTubeEntryPage(page.items.map { it.toCatalogueEntry() }, page.continuation)
    } else {
        val page = YouTube.artistItemsContinuation(continuation).getOrThrow()
        YouTubeEntryPage(page.items.map { it.toCatalogueEntry() }, page.continuation)
    }

internal suspend fun JvmYouTubeBrowseTransport.catalogueWatchPage(
    browse: YouTubeBrowse.Watch,
    continuation: String?,
): YouTubeEntryPage {
    val page =
        YouTube.next(
            WatchEndpoint(videoId = browse.videoId, playlistId = browse.playlistId, params = browse.params),
            continuation = continuation,
        ).getOrThrow()
    return YouTubeEntryPage(page.items.map { it.toSongCatalogueEntry() }, page.continuation)
}

private fun WatchEndpoint.toWatchTarget(): YouTubeWatchTarget? {
    if (videoId == null && playlistId == null) return null
    return YouTubeWatchTarget(videoId, playlistId, params)
}

private fun BrowseEndpoint.toContentSection(): ContentSection? =
    ContentSection(browseId, params).takeIf { browseId.isNotBlank() }

private fun AlbumItem.toRelease(): YouTubeRelease =
    YouTubeRelease(
        entry = toCatalogueEntry(),
        year = year,
        type =
            when (releaseType) {
                AlbumReleaseType.SINGLE -> YouTubeReleaseType.SINGLE
                AlbumReleaseType.EP -> YouTubeReleaseType.EP
                AlbumReleaseType.ALBUM -> YouTubeReleaseType.ALBUM
            },
    )

/** The shared mapping, kept in step with the one in [JvmYouTubeBrowseTransport]. */
internal fun YTItem.toCatalogueEntry(): YouTubeEntry =
    when (this) {
        is SongItem -> toSongCatalogueEntry()
        is AlbumItem ->
            YouTubeEntry(
                kind = ContentKind.ALBUM,
                id = browseId,
                title = title,
                subtitle = artists.orEmpty().joinToString(", ") { it.name },
                artworkUri = thumbnail,
                explicit = explicit,
                artists = artists.orEmpty().toCatalogueCredits(),
            )
        is PlaylistItem ->
            YouTubeEntry(
                // A podcast show arrives playlist-shaped; its id prefix names the show page it opens.
                kind = if (isYouTubePodcastShowId(id)) ContentKind.PODCAST else ContentKind.PLAYLIST,
                id = id,
                title = title,
                subtitle = author?.name ?: songCountText.orEmpty(),
                artworkUri = thumbnail,
            )
        is PodcastItem ->
            YouTubeEntry(
                kind = ContentKind.PODCAST,
                id = browseId,
                title = title,
                subtitle = author?.name.orEmpty(),
                artworkUri = thumbnail,
            )
        is EpisodeItem ->
            YouTubeEntry(
                kind = ContentKind.EPISODE,
                id = id,
                title = title,
                subtitle = podcast?.name.orEmpty(),
                artworkUri = thumbnail,
                durationSeconds = duration,
            )
        is ArtistItem ->
            YouTubeEntry(
                kind = ContentKind.ARTIST,
                id = id,
                title = title,
                subtitle = subscriberCountText ?: monthlyListenerCountText.orEmpty(),
                artworkUri = thumbnail,
            )
    }

internal fun SongItem.toSongCatalogueEntry(): YouTubeEntry =
    YouTubeEntry(
        kind = if (isPodcast) ContentKind.EPISODE else ContentKind.SONG,
        id = id,
        title = title,
        subtitle = artists.joinToString(", ") { it.name },
        artworkUri = thumbnail,
        durationSeconds = duration,
        explicit = explicit,
        setVideoId = setVideoId,
        album = album?.name,
        artists = artists.toCatalogueCredits(),
        albumId = album?.id?.takeIf(String::isNotBlank),
    )

/** Credits keep the browse id the provider gave. A credit without a name is dropped, never shown blank. */
private fun List<Artist>.toCatalogueCredits(): List<ContentCredit> =
    mapNotNull { artist ->
        artist.name.takeIf(String::isNotBlank)?.let { ContentCredit(it, artist.id?.takeIf(String::isNotBlank)) }
    }
