package moe.rukamori.archivetune.shared.ui.screens.artistalbum

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentShelf
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest

/**
 * Presentation facts the provider or a library owner must supply. They are inputs here, never guessed by the UI:
 * a shelf layout decides list-versus-grid, and a release year/type is not carried by ContentItem.
 */
enum class ArtistShelfLayout { LIST, GRID }

enum class ArtistReleaseType { ALBUM, SINGLE, EP }

/** A release card. Provider and library adapters fill the year and type that ContentItem does not carry. */
data class ArtistReleaseUi(
    val album: ContentRef,
    val title: String,
    val artworkUri: String?,
    val year: Int?,
    val type: ArtistReleaseType,
)

/**
 * One provider shelf. [shelf] carries the section handle and the loaded first page. [more] is the complete section
 * request when the provider offers "view all"; a shelf without [more] is complete as delivered.
 */
data class ArtistShelfUi(
    val shelf: ContentShelf,
    val layout: ArtistShelfLayout,
    val more: ContentRequest? = null,
)

/**
 * Remote artist page. [play], [shuffle] and [radio] are provider watch/radio collections, which are always RADIO
 * queues because the provider owns their continuation; they are null when the provider offers none.
 */
data class ArtistPageUi(
    val artist: ContentItem,
    val description: String?,
    val monthlyListenersText: String?,
    val subscribersText: String?,
    val shelves: List<ArtistShelfUi>,
    val releases: List<ArtistReleaseUi>,
    val shareLink: String,
    val play: ContentRequest? = null,
    val shuffle: ContentRequest? = null,
    val radio: ContentRequest? = null,
)

/** Library view of the artist. Songs and albums are already filtered by the owner's explicit/video policy. */
data class ArtistLibraryUi(
    val name: String?,
    val artworkUri: String?,
    val isLocal: Boolean,
    val subscribed: Boolean,
    val songs: List<ContentOccurrence>,
    val albums: List<ArtistReleaseUi>,
)

/** Block state comes from the blocklist owner; Unknown means no record exists, Failed means the read failed. */
sealed interface ArtistBlockUiState {
    data object Loading : ArtistBlockUiState
    data class Known(val blocked: Boolean) : ArtistBlockUiState
    data object Unknown : ArtistBlockUiState
    data object Failed : ArtistBlockUiState
}

/**
 * Album page. [songs] must be the complete track list only when [songsComplete] is true; a partial list is never
 * presented as complete and never queued as the whole album.
 */
data class AlbumPageUi(
    val album: ContentItem,
    val artists: List<ContentItem>,
    val year: Int?,
    val bookmarked: Boolean,
    val songs: List<ContentOccurrence>,
    val songsComplete: Boolean,
    val otherVersions: List<ContentItem>,
)

/** Load state of the album page as the host resolves it. Error(notFound) gates the not-found copy. */
sealed interface AlbumLoadState {
    data object Loading : AlbumLoadState
    data object Content : AlbumLoadState
    data object Empty : AlbumLoadState
    data class Error(val notFound: Boolean) : AlbumLoadState
}

/** Apple-style looping cover artwork. The host renders it; the screen only decides when it may be shown. */
data class AlbumCanvasUi(val videoUrl: String)

typealias AlbumCanvasSlot = @Composable (canvas: AlbumCanvasUi, modifier: Modifier) -> Unit

/**
 * The current media item's content id and album id, plus play state, observed by the host and passed in. Active
 * highlighting compares content ids, matching the baseline, so a provider queue need not share occurrence ids.
 */
data class ArtistAlbumNowPlaying(
    val songId: String? = null,
    val albumId: String? = null,
    val isPlaying: Boolean = false,
)

enum class ArtistSongSortType { CREATE_DATE, NAME, PLAY_TIME }

/** Sort order is applied by the library repository request; the UI only displays and reports the choice. */
data class ArtistSongSort(val type: ArtistSongSortType, val descending: Boolean)

/** A menu target. The menu owner resolves local versus provider behavior from [ContentRef.provider]. */
data class ArtistAlbumMenuTarget(
    val ref: ContentRef,
    val occurrence: ContentOccurrence? = null,
)

/**
 * Every artist and album action crosses this boundary. Each member is abstract on purpose: the integrator implements
 * it against the single queue, library, download, menu and navigation owners. Nothing defaults to a silent success.
 */
interface ArtistAlbumHost {
    fun back()
    fun backToMain()

    /** Opens an artist, album, playlist or podcast detail route through the navigation owner. */
    fun openContent(ref: ContentRef)
    /** Release cards always open an album, preserving the provider's opaque browse ID. */
    fun openAlbum(ref: ContentRef) = openContent(ref.copy(kind = moe.rukamori.archivetune.shared.domain.repository.ContentKind.ALBUM))
    fun openArtistSongs(artist: ContentRef)
    fun openArtistAlbums(artist: ContentRef)
    /** Opens the complete provider section with the given layout; the items screen owns paging and complete play. */
    fun openArtistSection(request: ContentRequest, title: String, layout: ArtistShelfLayout)
    fun openDownloads()

    /** Requests the remote artist page; the host reports the result back through [ArtistScreen] inputs. */
    fun loadArtistPage(artist: ContentRef)
    fun loadAlbumPage(album: ContentRef)

    /** Local queue through QueueController.replace; [startOccurrenceId] must belong to [entries]. */
    fun playOccurrences(entries: List<ContentOccurrence>, startOccurrenceId: String)
    /**
     * Starts one song. A library song queues [context] (always a complete library list) from [song]; a provider song
     * starts that provider's watch queue for the song and ignores [context], so a partial provider page never becomes
     * a complete queue.
     */
    fun playSong(song: ContentOccurrence, context: List<ContentOccurrence>)
    /** Remote collection through ContinuationQueueController.load; it never plays a partial page as complete. */
    fun playCollection(request: QueueLoadRequest)
    fun togglePlayPause()

    /** Follow/bookmark through LibraryMutation.Like; the host creates the library row when it does not exist. */
    fun setLiked(ref: ContentRef, liked: Boolean)
    /** Blocklist write through the blocklist owner; the host guards concurrent toggles. */
    fun setArtistBlocked(artist: ContentRef, name: String, blocked: Boolean)

    /** Downloads only the songs that are missing; the host resolves quality from the download settings. */
    fun downloadSongs(songs: List<ContentRef>)
    fun removeDownloads(songs: List<ContentRef>)

    fun share(link: String)
    fun copyLink(link: String)
    fun openMenu(target: ArtistAlbumMenuTarget)
    fun openSelectionMenu(songs: List<ContentOccurrence>)

    /** Persists the library song sort; the host re-requests [ArtistSongsScreen] songs in that order. */
    fun setArtistSongSort(sort: ArtistSongSort)
}
