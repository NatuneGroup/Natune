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
 */

/** One shelf in a catalogue feed. [more] is the provider's own section endpoint for "see all", when it gave one. */
data class YouTubeShelf(
    val key: String,
    val title: String,
    val label: String? = null,
    val thumbnail: String? = null,
    val entries: List<YouTubeEntry>,
    val more: ContentSection? = null,
    /** Charts rank rows; [numbered] asks the host to number them. */
    val numbered: Boolean = false,
    /** A grid shelf lays out tiles; a list shelf lays out rows. */
    val grid: Boolean = false,
    /** The provider's column packing for the shelf rows; null leaves the host's default. */
    val numItemsPerColumn: Int? = null,
)

/** A feed page: its shelves, chips that re-request the feed, and the continuation for the next shelf batch. */
data class YouTubeShelfPage(
    val shelves: List<YouTubeShelf>,
    val continuation: String? = null,
    val chips: List<YouTubeFeedChip> = emptyList(),
)

/** A home feed chip: [params] selects it, [deselectParams] un-selects it when the provider gave one. */
data class YouTubeFeedChip(val title: String, val params: String?, val deselectParams: String?)

/** A mood or genre tile: the stripe color and the section it browses to. */
data class YouTubeMoodGenre(val title: String, val stripeArgb: Long, val section: ContentSection)

enum class YouTubeReleaseType { ALBUM, SINGLE, EP }

/** A new-release row: the album entry plus the year and type the provider gave for the chips. */
data class YouTubeRelease(val entry: YouTubeEntry, val year: Int?, val type: YouTubeReleaseType)

/** The explore page payload: the new-release shelf and the flat mood/genre tile list. */
data class YouTubeExplore(val releases: List<YouTubeRelease>, val moods: List<YouTubeMoodGenre>)

/** One section of an artist page. [more] is the provider's "see all" endpoint when it gave one. */
data class YouTubeArtistSection(
    val title: String,
    val entries: List<YouTubeEntry>,
    val more: ContentSection?,
    val grid: Boolean,
)

/** A play/shuffle/radio endpoint the provider gave on an artist page. One id is always present. */
data class YouTubeWatchTarget(val videoId: String?, val playlistId: String?, val params: String?)

/** The remote artist page. [shelves] keep the provider order; [releases] is the same data seen flat. */
data class YouTubeArtistDetail(
    val artist: YouTubeEntry,
    val subscribersText: String?,
    val monthlyListenersText: String?,
    val description: String?,
    val shelves: List<YouTubeArtistSection>,
    val releases: List<YouTubeRelease>,
    val shareLink: String,
    val play: YouTubeWatchTarget? = null,
    val shuffle: YouTubeWatchTarget? = null,
    val radio: YouTubeWatchTarget? = null,
)

/** One dated section of an account history feed (the provider supplies the date label itself). */
data class YouTubeHistorySection(val title: String, val entries: List<YouTubeEntry>)

/**
 * The desktop catalogue transport. The core client is an object shared with [JvmYouTubeBrowseTransport], so
 * the session that transport applies (cookie, visitor data, login-for-browse) covers these reads as well.
 */
class JvmYouTubeCatalogueTransport {

    /** The home feed: chips on the first page, shelf sections and the continuation for the next batch. */
    suspend fun home(continuation: String? = null, params: String? = null): YouTubeShelfPage {
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
    suspend fun artist(browseId: String): YouTubeArtistDetail {
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
    suspend fun charts(continuation: String? = null): YouTubeShelfPage {
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
    suspend fun explore(): YouTubeExplore {
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
    suspend fun newReleases(): List<YouTubeRelease> =
        YouTube.newReleaseAlbums().getOrThrow().map { it.toRelease() }

    /** The account's YouTube listen history sections; a signed-out or failed read throws. */
    suspend fun historySections(): List<YouTubeHistorySection> =
        YouTube.musicHistory().getOrThrow().sections.orEmpty().map { section ->
            YouTubeHistorySection(section.title, section.songs.map { it.toSongCatalogueEntry() })
        }
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
                kind = ContentKind.PLAYLIST,
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
