package moe.rukamori.archivetune.shared.ui.menu.playerqueue

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import moe.rukamori.archivetune.shared.domain.settings.PlayerDesignStyle
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

/**
 * Where the overflow menu was opened. Queue commands always name [QueueItem.occurrenceId]: a song that appears twice
 * in the queue must never be resolved by its content id.
 */
sealed interface PlayerQueueMenuTrigger {
    data object Player : PlayerQueueMenuTrigger

    data class QueueItem(
        val occurrenceId: String,
        /** The baseline offers "Play next" from the queue only on rows that supply that command. */
        val playNextFromQueueAvailable: Boolean,
        /**
         * The baseline shows Remove, Play next and Add to queue together, and only where the surface supplies
         * removal. The Apple Music queue sheet supplies neither flag.
         */
        val removeFromQueueAvailable: Boolean,
    ) : PlayerQueueMenuTrigger
}

/**
 * Artist projection for the menu. A null [id] keeps the name visible but disables navigation, as in the baseline.
 * [thumbnailUrl] is resolved by the owner (database or network lookup) before the menu opens.
 */
data class PlayerQueueArtist(
    val id: String?,
    val name: String,
    val thumbnailUrl: String? = null,
)

data class PlayerQueueAlbum(
    val id: String,
    val title: String,
)

/** UI projection of the playing or queued song. It is not a queue or provider DTO; the owners keep those types. */
data class PlayerQueueTrack(
    val mediaId: String,
    val title: String,
    val artists: List<PlayerQueueArtist>,
    val album: PlayerQueueAlbum?,
    val thumbnailUrl: String?,
    val isLocal: Boolean,
    val localMimeType: String? = null,
)

/** Maps the host download state. [IN_PROGRESS] covers both queued and downloading, which share one row. */
enum class PlayerQueueDownloadState { NOT_DOWNLOADED, IN_PROGRESS, COMPLETED }

/** The finite YouTube playlist loader state shown by the recovery section and dialog. */
data class PlayerQueuePlaylistLoadState(
    val showDialog: Boolean,
    val loading: Boolean,
    val incomplete: Boolean,
    val wholeAction: Boolean,
)

/** Projection of the host sleep timer. [triggerAtEpochMs] is 0 when no duration is set. */
data class PlayerSleepTimerState(
    val isActive: Boolean,
    val pauseWhenSongEnd: Boolean,
    val triggerAtEpochMs: Long,
)

/**
 * Quality badge chosen by the host. [LOSSLESS_DECLARED] means the provider declares it and nothing was verified. Deezer
 * rows use [LOSSLESS_DECLARED] only for an account that carries a premium flag, and [MP3_320] otherwise, as the
 * baseline's credential check did.
 */
enum class PlayerSourceQuality { NONE, AAC_256, AAC_320, MP3_320, LOSSLESS_DECLARED }

/**
 * A row returned by a source search. [isYouTubeSong] rows seed a YouTube radio instead of a source override. The host
 * sets [quality] and [thumbnailUrl] per provider, matching the baseline's per-source fallbacks.
 */
data class PlayerSourceSearchResult(
    val source: AudioSourceType,
    val trackId: String,
    val title: String,
    val artist: String,
    val album: String?,
    val thumbnailUrl: String?,
    val durationMs: Long?,
    val quality: PlayerSourceQuality,
    val isYouTubeSong: Boolean,
)

/**
 * The override shape the host must persist for a picked source. The variant mirrors the per-source identity the
 * baseline passes to the playback service: Amazon by its row metadata, Qobuz by catalogue id, Qobuz Backup by its
 * mirror video id, and every other source by source alone.
 */
sealed interface PlayerSourceOverrideRequest {
    val source: AudioSourceType

    data class Plain(override val source: AudioSourceType) : PlayerSourceOverrideRequest

    data class AmazonPick(
        val title: String,
        val artist: String,
        val album: String?,
        val durationMs: Long?,
    ) : PlayerSourceOverrideRequest {
        override val source: AudioSourceType get() = AudioSourceType.AMAZON
    }

    data class QobuzTrack(val trackId: String) : PlayerSourceOverrideRequest {
        override val source: AudioSourceType get() = AudioSourceType.QOBUZ
    }

    data class QobuzBackupVideo(val videoId: String) : PlayerSourceOverrideRequest {
        override val source: AudioSourceType get() = AudioSourceType.QOBUZ_BACKUP
    }
}

enum class PlayerQueueCanvasRefetchResult { SUCCESS, FAILURE, ALREADY_RUNNING }

/**
 * Which way the host collapses the player sheet before navigating. Direct View artist and View album snap; the artist
 * picker collapses softly, as the baseline does.
 */
enum class PlayerQueueSheetCollapse { SNAP, SOFT }

/** Semantic icon keys. The host binds each key to its drawable set, so no icon asset is copied into this package. */
enum class PlayerQueueMenuIcon {
    RADIO,
    SYNC,
    SHARE,
    LINK,
    TUNE,
    BEDTIME,
    MOTION_PHOTOS,
    PLAYLIST_ADD,
    BOOKMARK,
    BOOKMARK_FILLED,
    ARTIST,
    ALBUM,
    OFFLINE,
    DOWNLOAD,
    PLAYLIST_PLAY,
    DELETE,
    QUEUE_MUSIC,
    GRAPHIC_EQ,
    INFO,
    EQUALIZER,
    SPEED,
    DISCOVER_TUNE,
    REMOVE,
    ADD,
    SKIP_NEXT,
    CLOSE,
    /** The placeholder mark for artwork that is missing. */
    MUSIC_NOTE,
    /** The generic provider mark the baseline used for Apple, Amazon and QQ, which has no dedicated asset. */
    PROVIDER_GENERIC,
    SEARCH,
    PROVIDER_TIDAL,
    PROVIDER_QOBUZ,
    PROVIDER_DEEZER,
    PROVIDER_JIOSAAVN,
    SPOTIFY,
    PLAY,
    /** The selected-state mark a source filter chip shows in place of its provider icon. */
    DONE,
    CAST,
    CAST_CONNECTED,
}

/**
 * Draws one semantic icon in the given tint and size. The host supplies the drawable binding, and the caller decides the
 * size with [modifier] (the baseline used 24 dp rows, 28 dp grid tiles and 18 to 22 dp inline marks).
 */
typealias PlayerQueueMenuIconSlot = @Composable (icon: PlayerQueueMenuIcon, tint: Color, modifier: Modifier) -> Unit

/** Draws artwork for a URL, or the host's placeholder when the URL is null. Artwork is cropped to fill [modifier]. */
typealias PlayerQueueArtworkSlot = @Composable (imageUrl: String?, modifier: Modifier) -> Unit

/**
 * Host-supplied flags and projections. Every flag is an explicit owner input; nothing here fakes platform
 * availability. [castAvailable] false means the host has no Cast capability, and [searchableSources] lists the
 * providers whose song search the host has wired. [appleMusicExperience] is the host's coordinated Apple Music
 * presentation, which drops the header card and replaces it with a divider, as the baseline did.
 */
data class PlayerQueueMenuState(
    val style: PlayerDesignStyle,
    val appleMusicExperience: Boolean,
    val download: PlayerQueueDownloadState,
    val pinnedToSpeedDial: Boolean,
    val externalDownloaderEnabled: Boolean,
    val canvasFeatureEnabled: Boolean,
    val lowDataModeActive: Boolean,
    val hasCanvasArtwork: Boolean,
    val canvasRefetching: Boolean,
    val castAvailable: Boolean,
    val castConnected: Boolean,
    val artistSeparators: String,
    val playlistLoad: PlayerQueuePlaylistLoadState,
    val playbackSpeed: Float,
    val playbackPitch: Float,
    val sleepTimer: PlayerSleepTimerState,
    val sourceChoices: List<AudioSourceType>,
    val selectedSource: AudioSourceType?,
    val amazonSearchEnabled: Boolean,
    val searchableSources: Set<AudioSourceType>,
    /** A host without an always-on display surface hides the AOD row instead of reporting a no-op. */
    val alwaysOnDisplaySupported: Boolean = true,
    /** A host without a radio-capable surface hides the Start radio grid tile. */
    val radioSupported: Boolean = true,
    /** A host without the acoustic graph hides the acoustic quality row. */
    val acousticQualitySupported: Boolean = true,
    /** A host with no equalizer UI hides the equalizer row. */
    val equalizerSupported: Boolean = true,
    /** A host with no download owner hides the download and remove-download rows. */
    val downloadsSupported: Boolean = true,
)

/**
 * Host clock for the sleep countdown. The menu never reads a platform clock, so the owner supplies time and tests can
 * inject a fixed one.
 */
fun interface PlayerQueueClock {
    fun nowEpochMs(): Long
}

/**
 * Every command the player and queue menus can issue. Each member is abstract with no default, so a missing owner
 * backend is a compile-time gap for the adopter instead of a silent no-op. Dialogs whose owner has not published a
 * port (equalizer, acoustic graph, playlist picker, save canvas) are commands here, and the owner renders them.
 *
 * Dismissal: the menu closes itself before the navigation and external-open commands ([openArtist], [openAlbum],
 * [openWithExternalDownloader], [openAcousticQuality], [showDetails]) and after the one-shot commands that end a
 * menu session. It stays open for [addToPlaylist], [saveCanvas], [openEqualizer], [cast], [downloadTrack] and
 * [removeDownload]. The source chooser closes only its own dialog, and the sleep timer closes the menu once a duration
 * or end-of-song choice is committed.
 */
interface PlayerQueueMenuActions : PlayerSleepTimerCommands {
    fun startRadio(track: PlayerQueueTrack)
    fun playNextFromQueue(occurrenceId: String)
    fun removeFromQueue(occurrenceId: String)
    fun playNext(track: PlayerQueueTrack)
    fun addToQueue(track: PlayerQueueTrack)
    fun setAodMode()
    /** The menu clamps both values to the baseline's tempo and pitch ranges before calling. */
    fun applyPlaybackParameters(speed: Float, pitch: Float)
    /** Owner renders the in-app equalizer and launches the system panel only when a handler exists. */
    fun openEqualizer()
    /** Owner renders the acoustic graph dialog for the target, as the baseline's AcousticQualityDialog did. */
    fun openAcousticQuality(track: PlayerQueueTrack)
    /** Owner runs the Cast permission check and route picker. */
    fun cast(track: PlayerQueueTrack)
    fun refreshSongSources(track: PlayerQueueTrack)
    /**
     * Runs one search across the providers that [sourceFilter] selects, with Amazon included only when [includeAmazon]
     * is true. The dialog treats a thrown exception as no results and lets cancellation propagate, so the owner may
     * fail a provider without failing the whole search.
     */
    suspend fun searchSongSources(
        query: String,
        sourceFilter: AudioSourceType?,
        includeAmazon: Boolean,
    ): List<PlayerSourceSearchResult>
    fun chooseSongSource(track: PlayerQueueTrack, source: AudioSourceType?)
    fun chooseSongSourceSearchResult(track: PlayerQueueTrack, request: PlayerSourceOverrideRequest)
    fun playYouTubeSearchResult(track: PlayerQueueTrack, result: PlayerSourceSearchResult)
    /** Owner renders the playlist picker and persists the chosen song, as the baseline's AddToPlaylistDialog did. */
    fun addToPlaylist(track: PlayerQueueTrack)
    fun toggleSpeedDialPin(track: PlayerQueueTrack)
    /** The owner inserts the database row, prewarms the cache and enqueues the download. */
    fun downloadTrack(track: PlayerQueueTrack)
    fun removeDownload(track: PlayerQueueTrack)
    /**
     * The URL is built by the menu; the owner handles the unconfigured-package and not-installed feedback, which the
     * baseline showed as toasts.
     */
    fun openWithExternalDownloader(url: String)
    /** The URL is built by the menu; the owner writes the clipboard and shows the copied feedback. */
    fun copyLink(url: String)
    /** The owner builds the share intent for the local file, using the track's mime type. */
    fun shareLocalAudio(track: PlayerQueueTrack)
    /** The owner shows the details dialog for the track. */
    fun showDetails(track: PlayerQueueTrack)
    /** Navigates to the artist. [PlayerQueueSheetCollapse.SNAP] collapses instantly; [PlayerQueueSheetCollapse.SOFT] animates. */
    fun openArtist(artist: PlayerQueueArtist, collapse: PlayerQueueSheetCollapse)
    /** Always collapses the sheet instantly, as the baseline's direct View album does. */
    fun openAlbum(album: PlayerQueueAlbum)
    /**
     * Owner runs the canvas refetch. [PlayerQueueCanvasRefetchResult.SUCCESS] closes the menu. FAILURE is reported by the
     * owner with the `m5pq_canvas_refetch_failed` text, which is the baseline's toast.
     */
    suspend fun refetchCanvas(track: PlayerQueueTrack, requireVertical: Boolean): PlayerQueueCanvasRefetchResult
    /** Owner renders the save canvas dialog, as the baseline's SaveCanvasDialog did. */
    fun saveCanvas(track: PlayerQueueTrack)
    fun cancelPlaylistLoading()
    fun retryPlaylistLoading()
    fun reloadFinitePlaylist()
    fun dismissPlaylistLoadError()
}

/** An artist that can be navigated to. The baseline hides artists without an id. */
internal fun navigableArtists(track: PlayerQueueTrack): List<PlayerQueueArtist> = track.artists.filter { it.id != null }

/** One picker entry. Names split on any separator character; each part keeps the original artist for navigation. */
internal data class PlayerQueueSplitArtist(
    val name: String,
    val original: PlayerQueueArtist,
)

/**
 * Splits artist names on the configured separator characters, matching the baseline's per-character class.
 * An empty separator list keeps the names whole.
 */
internal fun splitPlayerQueueArtists(
    artists: List<PlayerQueueArtist>,
    separators: String,
): List<PlayerQueueSplitArtist> {
    if (separators.isEmpty()) return artists.map { PlayerQueueSplitArtist(it.name, it) }
    val delimiters = separators.toCharArray()
    return artists.flatMap { artist ->
        val parts =
            artist.name
                .split(*delimiters)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        if (parts.size > 1) {
            parts.map { PlayerQueueSplitArtist(it, artist) }
        } else {
            listOf(PlayerQueueSplitArtist(artist.name, artist))
        }
    }
}
