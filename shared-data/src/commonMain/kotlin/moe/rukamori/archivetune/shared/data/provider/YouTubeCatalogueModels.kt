package moe.rukamori.archivetune.shared.data.provider

import moe.rukamori.archivetune.shared.domain.repository.ContentSection

/**
 * The shelf-level YouTube catalogue contract, shared by the hosts: the home feed, charts, moods & genres, new
 * releases and generic browse shelves. A host supplies the HTTP session; the shelf shapes and their mapping to
 * [YouTubeEntry] stay in the data module, so the iOS transport and the desktop transport read one vocabulary.
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
    /** The provider's own page title, when it sends one (browse/category pages). */
    val title: String? = null,
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

/**
 * The shelf feeds a host can serve. A failed request throws, so a host shows its failed state rather than an empty
 * shelf that looks loaded. A generic browse section, the remote artist page and the account history keep their own
 * platform surfaces: the hosts mount browse sections through the typed catalogue instead.
 */
interface YouTubeCatalogueTransport {
    /** The home feed: chips and shelves on the first page, then the shelf continuation. */
    suspend fun home(continuation: String? = null, params: String? = null): YouTubeShelfPage

    /** The charts page: ranked top songs first, then the genre and new-release shelves. */
    suspend fun charts(continuation: String? = null): YouTubeShelfPage

    /** The explore page: the new-release shelf plus the flat mood/genre tiles Discovery and Moods show. */
    suspend fun explore(): YouTubeExplore

    /** The new-releases page: every album the endpoint returned, in the provider's order. */
    suspend fun newReleases(): List<YouTubeRelease>
}
