package moe.rukamori.archivetune.shared.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import moe.rukamori.archivetune.shared.domain.library.LibraryFilter
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.ui.home.*
import moe.rukamori.archivetune.shared.ui.home.variants.BitChordHomeActions
import moe.rukamori.archivetune.shared.ui.home.variants.BitChordHomeUi
import moe.rukamori.archivetune.shared.ui.home.variants.HomeVariantChip
import moe.rukamori.archivetune.shared.ui.home.variants.HomeVariantImage
import moe.rukamori.archivetune.shared.ui.home.variants.HomeVariantNowPlaying
import moe.rukamori.archivetune.shared.ui.home.variants.HomeVariantThumbnailAspectRatio
import moe.rukamori.archivetune.shared.ui.home.variants.NatuneClassicHomeActions
import moe.rukamori.archivetune.shared.ui.home.variants.NatuneClassicHomeUi
import moe.rukamori.archivetune.shared.ui.home.variants.SpotifyHomeActions
import moe.rukamori.archivetune.shared.ui.home.variants.SpotifyHomeUi
import moe.rukamori.archivetune.shared.ui.screens.*
import moe.rukamori.archivetune.shared.ui.screens.artistalbum.*
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentAvatar
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentTogetherOwnerBinding
import moe.rukamori.archivetune.shared.ui.screens.commenttogether.CommentThumbnail
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoActions
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.MediaInfoUi
import moe.rukamori.archivetune.shared.ui.screens.mediainfo.TrackInfoUi

/**
 * Controlled UI bindings, not repositories or serialized transport models. The root supplies real
 * snapshots/operations for the resolved route; the graph supplies screen composition and navigation.
 */
sealed interface ArchiveGraphPage {
    /**
     * The generic feed, for the Classic and Muzo designs on the YouTube source only. Spotify, QQ, the Natune Classic
     * home and BitChord bind their own pages below, so the generic feed never stands in for a design it does not
     * implement.
     */
    data class Home(
        val source: HomeSource, val availableSources: List<HomeSource>, val sourceChange: (HomeSource) -> Unit,
        val design: HomeFeedDesign, val shelves: List<HomeShelf>, val page: PagedContentState,
        val loadMore: () -> Unit, val retry: () -> Unit,
        /** Greeting name from the account owner; blank shows the anonymous line. [blurEffects] is the host blur policy. */
        val accountName: String = "", val blurEffects: Boolean = true,
        /** Now-playing ids drive the active overlay; [refresh] enables the pull gesture and [refreshing] drives its line. */
        val activeIds: Set<String> = emptySet(), val playing: Boolean = false,
        val refreshing: Boolean = false, val refresh: (() -> Unit)? = null,
        /** Per-item frame ratio from the owner (crop-to-square policy and video metadata). Square when the owner supplies none. */
        val thumbnailAspectRatio: (ContentItem) -> Float = { HomeSquareCardRatio },
        /**
         * The feed's category chips and the selected chip's key, null while nothing filters the feed. [selectChip]
         * asks the owner to re-read the feed under the tapped chip, and a re-tap on the selected one clears it; the
         * owner never answers with a made-up shelf. [showChips] is the host's category-chips preference: off hides
         * the row, and the owner keeps the feed unfiltered while it is hidden.
         */
        val chips: List<HomeVariantChip> = emptyList(),
        val selectedChipKey: String? = null,
        val selectChip: (HomeVariantChip) -> Unit = {},
        val showChips: Boolean = true,
    ) : ArchiveGraphPage
    /**
     * Classic Home. The variant owns its greeting, chips, pull gesture and load-more trigger; [image] draws avatars and
     * thumbnails. [thumbnailAspectRatio] is the owner's per-item card frame ratio, square when none is supplied.
     */
    data class HomeNatuneClassic(val source: HomeSourceBinding, val state: NatuneClassicHomeUi,
        val actions: NatuneClassicHomeActions, val nowPlaying: HomeVariantNowPlaying, val image: HomeVariantImage,
        val thumbnailAspectRatio: HomeVariantThumbnailAspectRatio = { HomeSquareCardRatio }) : ArchiveGraphPage
    /**
     * Spotify Home. [blurEffects] is the host blur policy, passed to the variant surface unchanged. [thumbnailAspectRatio]
     * is the owner's album and playlist card frame ratio, square when none is supplied.
     */
    data class HomeSpotify(val source: HomeSourceBinding, val state: SpotifyHomeUi, val actions: SpotifyHomeActions,
        val nowPlaying: HomeVariantNowPlaying, val blurEffects: Boolean = true,
        val thumbnailAspectRatio: HomeVariantThumbnailAspectRatio = { HomeSquareCardRatio }) : ArchiveGraphPage
    /**
     * BitChord Home. The shell draws [accountName]'s greeting above the page and the baseline backdrop when [blurEffects]
     * is on; the variant owns its load-more trigger. [thumbnailAspectRatio] is the owner's shelf card frame ratio.
     */
    data class HomeBitChord(val source: HomeSourceBinding, val state: BitChordHomeUi, val actions: BitChordHomeActions,
        val nowPlaying: HomeVariantNowPlaying, val accountName: String = "", val blurEffects: Boolean = true,
        val thumbnailAspectRatio: HomeVariantThumbnailAspectRatio = { HomeSquareCardRatio }) : ArchiveGraphPage
    /** QQ Music Home. [activeRef] and [isPlaying] drive the active row; [refreshing] drives the pull indicator. */
    data class HomeQq(val source: HomeSourceBinding, val state: QqHomeUi, val actions: QqHomeActions,
        val activeRef: ContentRef?, val isPlaying: Boolean, val refreshing: Boolean = false) : ArchiveGraphPage
    data class Search(
        val query: String, val scope: SearchScope, val provider: ContentProvider, val page: PagedContentState,
        val queryChange: (String) -> Unit, val scopeChange: (SearchScope) -> Unit,
        val voice: (() -> Unit)?, val recognition: (() -> Unit)?, val loadMore: () -> Unit, val retry: () -> Unit,
        val filter: ContentSearchFilter = ContentSearchFilter.ALL,
        val discovery: Discovery? = null,
    ) : ArchiveGraphPage
    data class Library(
        val filter: LibraryFilter, val order: List<LibraryFilter>, val filterChange: (LibraryFilter) -> Unit,
        val page: PagedContentState, val loadMore: () -> Unit, val retry: () -> Unit,
        /** The My top size the Library's More menu offers, or null while that entry is hidden or the preference is unread. */
        val myTopSize: Int? = null,
        /** The mixed section's header. Null while the host has none to show. */
        val mixHeader: LibraryMixHeaderBinding? = null,
    ) : ArchiveGraphPage
    data class Discovery(val state: DiscoveryUi, val tab: (DiscoveryTab) -> Unit, val refresh: () -> Unit, val retry: () -> Unit) : ArchiveGraphPage
    /**
     * The charts sheet: ranked shelves first, then genre and new-release shelves. [continuation] is the next shelf
     * batch's token and [loadMore] pages it in; a host that reaches the list end with a token still open asks for it.
     */
    data class Charts(val sections: List<BrowseShelfUi>, val loading: Boolean, val failed: Boolean, val retry: () -> Unit,
        val continuation: String? = null, val loadMore: () -> Unit = {}) : ArchiveGraphPage
    data class Moods(val moods: List<MoodGenreUi>, val loading: Boolean, val failed: Boolean, val retry: () -> Unit) : ArchiveGraphPage
    data class Browse(val title: String, val collection: ContentRequest, val page: PagedContentState,
        val loadMore: () -> Unit, val retry: () -> Unit) : ArchiveGraphPage
    data class Releases(val state: ReleasesUi, val category: (ReleaseCategory) -> Unit,
        val query: (String) -> Unit, val refresh: () -> Unit, val more: ((ContentItem) -> Unit)?) : ArchiveGraphPage
    data class News(val state: NewsUi, val query: (String) -> Unit, val refresh: () -> Unit, val markRead: (Long) -> Unit) : ArchiveGraphPage
    data class Article(val state: NewsArticleUi, val retry: () -> Unit) : ArchiveGraphPage
    data class Podcasts(val state: PodcastBrowseUi, val query: (String) -> Unit, val subscriptionsOnly: (Boolean) -> Unit,
        val subscribe: ((ContentRef, Boolean) -> Unit)?, val retry: () -> Unit, val loadMore: () -> Unit) : ArchiveGraphPage
    data class Podcast(val state: PodcastUi, val subscribe: ((Boolean) -> Unit)?, val retry: () -> Unit, val loadMore: () -> Unit) : ArchiveGraphPage
    data class LocalSongs(val state: LocalSongsUi, val actions: LocalSongsActions) : ArchiveGraphPage
    /** The signed-in YouTube Music uploads shelf, its running transfers and the confirmed delete. */
    data class Uploads(val state: UploadsUi, val actions: UploadsActions) : ArchiveGraphPage
    data class Downloads(val state: DownloadsUi, val actions: moe.rukamori.archivetune.shared.ui.screens.DownloadActions) : ArchiveGraphPage
    data class History(val state: HistoryUi, val actions: HistoryActions) : ArchiveGraphPage
    data class Statistics(val state: StatisticsUi, val actions: StatisticsActions,
        val formatDuration: (Long) -> String, val formatWeekday: (Int) -> String) : ArchiveGraphPage
    data class YearInMusic(val state: YearInMusicUi, val actions: YearInMusicActions,
        val formatDuration: (Long) -> String) : ArchiveGraphPage
    /** The owner supplies the signed-in library snapshots and the long-press menu; items route through ArchiveRoutes. */
    data class Account(val state: AccountUi, val select: (AccountContentType) -> Unit,
        val more: ((ContentItem) -> Unit)?) : ArchiveGraphPage
    /** The owner runs the web attempt and commits the account; the host supplies the native web surface and keys it per attempt. */
    data class Login(val state: YouTubeLoginUi, val actions: YouTubeLoginActions,
        val webSurface: @Composable (Modifier) -> Unit) : ArchiveGraphPage
    data class Playlists(val state: PlaylistCollectionUi, val actions: PlaylistCollectionActions) : ArchiveGraphPage
    data class Tags(val state: PlaylistTagsUi, val actions: PlaylistTagsActions) : ArchiveGraphPage
    /** The Telegram owner resolves, persists and signs in; the host rebinds back and bot-chat navigation. */
    data class TelegramBots(val state: TelegramBotsUi, val actions: TelegramBotsActions) : ArchiveGraphPage
    /** The Telegram owner searches and materialises channels; the host rebinds back and opens the resulting playlist. */
    data class TelegramBrowse(val state: TelegramBrowseUi, val actions: TelegramBrowseActions,
        val avatar: @Composable (TelegramChannelUi, Modifier) -> Unit) : ArchiveGraphPage
    /** The Telegram owner collects replies, persists tracks, plays, downloads and forwards; the host rebinds back and supplies artwork. */
    data class TelegramBotChat(val state: TelegramBotChatUi, val actions: TelegramBotChatActions,
        val artwork: @Composable (TelegramBotTrackUi, Modifier) -> Unit) : ArchiveGraphPage
    /**
     * Capture, history and settings come from the recognition owner. [autoStartRequest] is the token of one listen-again
     * request from the route (`route.arguments["autoStart"]?.toLongOrNull()`); null means no request is pending.
     */
    data class MusicRecognition(val state: MusicRecognitionUi, val actions: MusicRecognitionActions,
        val artwork: RecognitionArtwork, val autoStartRequest: Long? = null) : ArchiveGraphPage
    /** [track] is the owner's saved result for the route key; null when the owner no longer holds it. */
    data class MusicRecognitionDetails(val track: RecognizedTrackUi?, val actions: MusicRecognitionDetailsActions,
        val artwork: RecognitionArtwork) : ArchiveGraphPage
    /**
     * Playlists and podcasts. Artist and album routes bind [Artist] and [Album] to the specialized M5 screens. [control]
     * is the retained sort, order or period control of a playlist page, supplied by its owner; null keeps the layout.
     */
    data class Detail(
        val kind: DetailKind,
        val header: ContentItem?,
        val page: PagedContentState,
        val actions: DetailActions,
        val control: PlaylistDetailControl? = null,
        /** A local playlist's first rows' items, drawn as its header's 2x2 mosaic when it has no custom cover. */
        val headerMosaicItems: List<ContentItem> = emptyList(),
    ) : ArchiveGraphPage
    /**
     * Artist page from the artist/album owner. [page] is null while the provider page loads and [pageFailed] reports a
     * failed load. [library] is the signed-in library view and [blockState] comes from the blocklist owner.
     */
    data class Artist(val artist: ContentRef, val page: ArtistPageUi?, val pageFailed: Boolean, val library: ArtistLibraryUi?,
        val blockState: ArtistBlockUiState, val nowPlaying: ArtistAlbumNowPlaying, val host: ArtistAlbumHost) : ArchiveGraphPage
    /** The artist's library songs, already filtered and ordered by [sort] from the library owner. */
    data class ArtistSongs(val artistName: String, val songs: List<ContentOccurrence>, val sort: ArtistSongSort,
        val nowPlaying: ArtistAlbumNowPlaying, val host: ArtistAlbumHost) : ArchiveGraphPage
    /** The artist's albums, titled by the owner. */
    data class ArtistAlbums(val title: String, val albums: List<ArtistReleaseUi>, val nowPlaying: ArtistAlbumNowPlaying,
        val host: ArtistAlbumHost) : ArchiveGraphPage
    /** One complete provider section. [repository] pages it; the route supplies [request] and [layout]. */
    data class ArtistItems(val title: String, val request: ContentRequest, val layout: ArtistShelfLayout,
        val repository: TypedContentRepository, val nowPlaying: ArtistAlbumNowPlaying, val host: ArtistAlbumHost) : ArchiveGraphPage
    /** Album page. [loadState] gates the loading, empty and not-found copy; [canvas] is the optional looping cover. */
    data class Album(val album: ContentRef, val page: AlbumPageUi?, val loadState: AlbumLoadState,
        val downloads: List<ContentDownload>, val nowPlaying: ArtistAlbumNowPlaying, val host: ArtistAlbumHost,
        val canvas: AlbumCanvasUi? = null, val canvasSlot: AlbumCanvasSlot? = null) : ArchiveGraphPage
    /** Room state and operations come from the Listen Together owner; avatars are host-supplied artwork. */
    data class ListenTogether(val state: ListenTogetherUi, val actions: ListenTogetherActions,
        val avatar: @Composable (RoomMemberUi, Modifier) -> Unit, val showHeader: Boolean = true) : ArchiveGraphPage
    /**
     * The shared comment-together screen for `listen_together/chat`, mounted through CommentTogetherBoundScreen over the
     * root-owned [owner]. The root builds one binding and calls its close when it tears the chat down, so the host never
     * closes it on route exit. Back is the binding's native back port, which the root supplies. [avatar] and [thumbnail]
     * are host artwork slots.
     */
    data class CommentTogether(val owner: CommentTogetherOwnerBinding, val avatar: CommentAvatar,
        val thumbnail: CommentThumbnail) : ArchiveGraphPage
    /** Track info and audio specs for `track_info/<id>`. The host opens media info from it; [artwork] draws the cover. */
    data class TrackInfo(val state: TrackInfoUi,
        val artwork: (@Composable (url: String, modifier: Modifier) -> Unit)?) : ArchiveGraphPage
    /** Media info for `media_info/<id>`. [liveAudioChain] is the playing-track audio-chain slot; null hides it. */
    data class MediaInfo(val state: MediaInfoUi, val actions: MediaInfoActions,
        val artwork: (@Composable (url: String, modifier: Modifier) -> Unit)?,
        val liveAudioChain: (@Composable () -> Unit)? = null) : ArchiveGraphPage

    /** An explicit mounted original/native or M6 screen, never a successful placeholder page. */
    data object Host : ArchiveGraphPage
}

class ArchiveGraphActions(
    /** The SAME queue/source owner resolves full metadata and continuation. Never a loaded-prefix queue. */
    val playOccurrence: (collection: ContentRequest?, start: QueueStart) -> Unit,
    val playCollection: (QueueLoadRequest) -> Unit,
    val more: ((ContentOccurrence) -> Unit)?,
    val openExternal: (String) -> Unit,
)

/**
 * The source switcher that heads a Home page. The page binds the owner's current source, the sources the owner allows and
 * the change request. The shell shows them and forwards the request; it never changes the source itself.
 */
data class HomeSourceBinding(val source: HomeSource, val available: List<HomeSource>, val change: (HomeSource) -> Unit)
