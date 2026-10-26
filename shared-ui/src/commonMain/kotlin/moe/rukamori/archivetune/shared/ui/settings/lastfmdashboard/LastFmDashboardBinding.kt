package moe.rukamori.archivetune.shared.ui.settings.lastfmdashboard
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardReader
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardService
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmDashboardSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.ui.resources.*


/**
 * What the dashboard needs from its host. The host owns the YouTube search, the player, the clipboard and the
 * navigation, so this module names none of them. Every member is required; none has a default.
 */
interface LastFmDashboardHost {
    /**
     * The YouTube song that best matches a Last.fm track, or null when nothing matches. Throws when the search fails.
     * The binding reports a throw the same way as no match, which is what the baseline showed. Runs on the caller's
     * dispatcher, so the host switches to IO itself.
     */
    suspend fun findYouTubeSong(artist: String?, title: String): ContentOccurrence?

    /** Whether the player can take queue changes right now. */
    fun isPlayerAvailable(): Boolean

    /** Starts a YouTube radio queue from [song]. */
    fun startRadio(song: ContentOccurrence)

    /** Plays [song] after the current item. */
    fun playNext(song: ContentOccurrence)

    /** Appends [song] to the current queue. */
    fun addToQueue(song: ContentOccurrence)

    /** Opens the add-to-playlist dialog for [track]. The host performs its own song lookup. */
    fun addToPlaylist(track: LastFmDashboardTrack)

    /** Puts [text] on the system clipboard. */
    fun copyText(text: String)

    /** Opens an external page, such as a Last.fm track or profile. */
    fun openUrl(url: String)

    /** Opens the genres page. The Last.fm tag is not passed through. */
    fun openGenres()

    /**
     * An image for a row that Last.fm did not supply, from the host's own library and catalogue chain. Null when
     * nothing is found. A failed lookup throws and is never cached. Runs on the caller's dispatcher; the host
     * switches to IO itself. A track request with [LastFmArtworkRequest.Track.preferYouTube] set puts YouTube
     * thumbnails ahead of library artwork, as the baseline's YouTube-thumbnail preference did.
     */
    suspend fun resolveArtwork(request: LastFmArtworkRequest): String?
}

/** What a dashboard row needs an image for. */
sealed interface LastFmArtworkRequest {
    data class Track(val title: String, val artist: String?, val preferYouTube: Boolean) : LastFmArtworkRequest

    data class Artist(val name: String) : LastFmArtworkRequest

    data class Album(val title: String, val artist: String?) : LastFmArtworkRequest
}

/**
 * Holds the dashboard state and runs its actions. [scope] belongs to the host, so a load or a track action finishes
 * even after the page leaves composition. The page only reads [state] and calls the functions below.
 */
class LastFmDashboardBinding(
    private val scope: CoroutineScope,
    private val reader: LastFmDashboardReader,
    private val service: LastFmDashboardService,
    private val host: LastFmDashboardHost,
) {
    private val mutableState = MutableStateFlow(LastFmDashboardUiState())
    val state: StateFlow<LastFmDashboardUiState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<LastFmDashboardMessage>(
        extraBufferCapacity = MESSAGE_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val messages: SharedFlow<LastFmDashboardMessage> = mutableMessages.asSharedFlow()

    private val cacheLock = Mutex()
    private val songs = HashMap<String, ContentOccurrence?>()
    private val artwork = HashMap<LastFmArtworkRequest, String?>()
    private var refreshJob: Job? = null
    private var sheetJob: Job? = null

    /** Loads the signed-in account. Requests nothing when no account is usable, and does nothing while a load runs. */
    fun refresh() {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            mutableState.update { it.copy(refreshing = true) }
            try {
                val load = reader.load()
                mutableState.update { current ->
                    if (load == null) {
                        current.copy(
                            checked = true,
                            signedIn = false,
                            loaded = false,
                            snapshot = null,
                            refreshing = false,
                            sheetTrack = null,
                            sheetGenre = null,
                            sheetArtwork = null,
                        )
                    } else {
                        // A failed section keeps its last value only for the same endpoint and account, so one
                        // account's snapshot never carries over into another.
                        val earlier = current.snapshot?.takeIf {
                            it.endpoint == load.endpoint && it.username.equals(load.username, ignoreCase = true)
                        }
                        val base = earlier ?: LastFmDashboardSnapshot(endpoint = load.endpoint, username = load.username)
                        current.copy(
                            checked = true,
                            signedIn = true,
                            loaded = true,
                            snapshot = base.refreshedWith(load),
                            refreshing = false,
                        )
                    }
                }
            } finally {
                // The reader lets only cancellation escape. This keeps the spinner from sticking if that ever changes.
                mutableState.update { if (it.refreshing) it.copy(refreshing = false) else it }
            }
        }
    }

    fun selectFilter(filter: LastFmDashboardFilter) {
        mutableState.update { it.copy(filter = filter) }
    }

    fun setSearchVisible(visible: Boolean) {
        mutableState.update { it.copy(searchVisible = visible, searchQuery = if (visible) it.searchQuery else "") }
    }

    fun setSearchQuery(query: String) {
        mutableState.update { it.copy(searchQuery = query) }
    }

    fun openProfile(url: String?) {
        val target = url?.trim()?.takeIf { it.isNotEmpty() } ?: return
        host.openUrl(target)
    }

    /** Shows the track sheet. Tags load in the background, and a YouTube thumbnail when Last.fm has no image. */
    fun openTrackSheet(track: LastFmDashboardTrack) {
        sheetJob?.cancel()
        mutableState.update {
            it.copy(sheetTrack = track, sheetGenre = null, sheetArtwork = track.lastFmArtwork)
        }
        sheetJob = scope.launch {
            val artist = track.artist?.trim()?.takeIf { it.isNotEmpty() }
            if (artist != null) {
                val genre = service.getTrackTags(artist, track.title).getOrNull()
                    ?.mapNotNull { tag -> tag.trim().takeIf { it.isNotEmpty() } }
                    ?.take(GENRE_TAGS)
                    ?.takeIf { it.isNotEmpty() }
                    ?.joinToString(", ")
                updateSheet(track) { it.copy(sheetGenre = genre) }
            }
            if (track.lastFmArtwork.isNullOrBlank()) {
                val thumbnail = try {
                    findSong(track)?.item?.artworkUri
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    null
                }
                if (!thumbnail.isNullOrBlank()) updateSheet(track) { it.copy(sheetArtwork = thumbnail) }
            }
        }
    }

    fun closeTrackSheet() {
        sheetJob?.cancel()
        mutableState.update { it.copy(sheetTrack = null, sheetGenre = null, sheetArtwork = null) }
    }

    /**
     * Resolves the sheet track on YouTube, then runs [action] on the match. As in the baseline, the sheet closes when
     * the action ends, including when nothing matched. A player that is not ready shows a message and keeps the sheet.
     */
    fun runTrackAction(action: LastFmTrackAction) {
        val track = mutableState.value.sheetTrack ?: return
        if (mutableState.value.loadingAction != null) return
        if (!host.isPlayerAvailable()) {
            mutableMessages.tryEmit(LastFmDashboardMessage.PLAYER_UNAVAILABLE)
            return
        }
        mutableState.update { it.copy(loadingAction = action) }
        scope.launch {
            try {
                val song = findSong(track)
                if (song == null) {
                    mutableMessages.tryEmit(LastFmDashboardMessage.NO_YOUTUBE_MATCH)
                } else {
                    when (action) {
                        LastFmTrackAction.START_MIX, LastFmTrackAction.PLAY -> host.startRadio(song)
                        LastFmTrackAction.PLAY_NEXT -> host.playNext(song)
                        LastFmTrackAction.ADD_TO_QUEUE -> host.addToQueue(song)
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutableMessages.tryEmit(LastFmDashboardMessage.NO_YOUTUBE_MATCH)
            } finally {
                mutableState.update { current ->
                    val idle = current.copy(loadingAction = null)
                    if (current.sheetTrack == track) {
                        idle.copy(sheetTrack = null, sheetGenre = null, sheetArtwork = null)
                    } else {
                        idle
                    }
                }
            }
        }
    }

    /** Closes the sheet and hands the track to the host, which shows the playlist dialog. */
    fun addToPlaylist() {
        val track = mutableState.value.sheetTrack ?: return
        closeTrackSheet()
        host.addToPlaylist(track)
    }

    fun openInLastFm() {
        val url = mutableState.value.sheetTrack?.url?.trim()?.takeIf { it.isNotEmpty() } ?: return
        closeTrackSheet()
        host.openUrl(url)
    }

    fun copySong() {
        val track = mutableState.value.sheetTrack ?: return
        closeTrackSheet()
        host.copyText(listOfNotNull(track.artist, track.title).joinToString(" - "))
        mutableMessages.tryEmit(LastFmDashboardMessage.SONG_COPIED)
    }

    fun openGenres() {
        closeTrackSheet()
        host.openGenres()
    }

    /** The image for a track row, or null. Results are cached per request; a failed lookup is retried later. */
    suspend fun resolveTrackArtwork(track: LastFmDashboardTrack, preferYouTube: Boolean): String? =
        resolveArtwork(LastFmArtworkRequest.Track(title = track.title, artist = track.artist, preferYouTube = preferYouTube))

    suspend fun resolveArtistArtwork(name: String): String? =
        resolveArtwork(LastFmArtworkRequest.Artist(name = name))

    suspend fun resolveAlbumArtwork(title: String, artist: String?): String? =
        resolveArtwork(LastFmArtworkRequest.Album(title = title, artist = artist))

    private suspend fun resolveArtwork(request: LastFmArtworkRequest): String? {
        cacheLock.withLock {
            if (artwork.containsKey(request)) return artwork[request]
        }
        val found = try {
            host.resolveArtwork(request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return null
        }
        val url = found?.takeIf { it.isNotBlank() }
        cacheLock.withLock {
            if (artwork.size >= CACHE_LIMIT) artwork.clear()
            artwork[request] = url
        }
        return url
    }

    /** The YouTube match for a track, cached per track. A failed search throws and is not cached. */
    private suspend fun findSong(track: LastFmDashboardTrack): ContentOccurrence? {
        val key = track.artworkKey
        cacheLock.withLock {
            if (songs.containsKey(key)) return songs[key]
        }
        val found = host.findYouTubeSong(track.artist, track.title)
        cacheLock.withLock {
            if (songs.size >= CACHE_LIMIT) songs.clear()
            songs[key] = found
        }
        return found
    }

    private fun updateSheet(track: LastFmDashboardTrack, change: (LastFmDashboardUiState) -> LastFmDashboardUiState) {
        mutableState.update { current -> if (current.sheetTrack == track) change(current) else current }
    }

    private companion object {
        const val CACHE_LIMIT = 256
        const val GENRE_TAGS = 3
        const val MESSAGE_BUFFER = 4
    }
}
