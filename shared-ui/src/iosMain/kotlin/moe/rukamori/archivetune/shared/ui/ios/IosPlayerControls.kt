package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.ui.graphics.Color
import moe.rukamori.archivetune.shared.ui.player.artworkPalette
import kotlinx.coroutines.flow.flow
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffState
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PlayerVolumeKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.RepeatModeKey
import moe.rukamori.archivetune.shared.domain.settings.SeekExtraSeconds
import moe.rukamori.archivetune.shared.domain.telegram.TelegramTrackRef
import moe.rukamori.archivetune.shared.ui.IosHost
import moe.rukamori.archivetune.shared.ui.navigation.ArchiveRoutes
import moe.rukamori.archivetune.shared.ui.player.playerOccurrence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import moe.rukamori.archivetune.shared.ui.player.PlayerAction
import moe.rukamori.archivetune.shared.ui.player.PlayerHandoff
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeControls
import moe.rukamori.archivetune.shared.ui.player.PlayerNativeState
import moe.rukamori.archivetune.shared.ui.player.PlayerRepeat

/** The seek step of one double tap, before the progressive multiplier. The same step as the Android player. */
private const val SEEK_STEP_MS = 5_000L

/** Taps inside this window grow the step while the progressive seek preference is on. The same window as Android. */
private const val SEEK_REPEAT_WINDOW_MS = 1_000L

/** How long the observed state stays subscribed after the player leaves composition. */
private const val STATE_STOP_TIMEOUT_MS = 5_000L

/**
 * The iOS native controls over the one session graph. Skip and repeat go through the queue owner and the settings
 * store, seek goes through the one player, like goes through the library owner, and shuffle goes through the queue
 * owner. The app-level volume goes through the graph onto the engine's mixer, like the Android player. Share opens
 * the system sheet on the playing song's catalogue link. Song actions without an owner on this host are reported
 * and never shown as done.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class IosPlayerControls(
    private val graph: IosSessionGraph,
    private val host: IosHost,
    private val sleepSheet: IosSleepTimerSheetState,
    private val itemMenu: IosItemMenuState,
    private val openRoute: (String) -> Unit,
) : PlayerNativeControls {
    private val clock = TimeSource.Monotonic
    private var lastSeekAt: TimeSource.Monotonic.ValueTimeMark? = null
    private var seekMultiplier = 1

    val outputPicker = MutableStateFlow(false)
    val artistChoices = MutableStateFlow<List<Pair<ContentRef, String>>>(emptyList())
    private val occurrence = combine(graph.queue.state, graph.player.snapshot) { queue, playback -> playerOccurrence(queue, playback) }
    private val liked: Flow<Pair<ContentRef?, Boolean>> = occurrence
        .map { it?.item?.ref }
        .distinctUntilChanged()
        .flatMapLatest { ref -> if (ref == null) flowOf(null to false) else graph.libraryContent.isLiked(ref).map { ref to it }.onStart { emit(ref to false) } }

    private val observed: Flow<PlayerNativeState> = combine(
        graph.queue.state,
        graph.player.snapshot,
        graph.settings.snapshots,
        liked,
        graph.handoff,
    ) { queue, playback, preferences, isLiked, handoff ->
        iosNativeState(queue, playback, preferences, isLiked.first == playerOccurrence(queue, playback)?.item?.ref && isLiked.second, handoff)
    }

    private val palette: Flow<Pair<String?, List<Color>>> = combine(
        occurrence.map { it?.item?.artworkUri }.distinctUntilChanged(),
        graph.artworkLoader.overrides ?: flowOf(null),
    ) { uri, overrides -> uri to graph.artworkLoader.displayUri(uri, overrides) }
        .distinctUntilChanged()
        .flatMapLatest { (uri, shown) ->
            flow {
                emit(shown to emptyList<Color>())
                val colors = try {
                    graph.artworkLoader.load(uri)?.let { image ->
                        withContext(Dispatchers.Default) { artworkPalette(image) }
                    }.orEmpty()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                emit(shown to colors)
            }
        }

    override val state: StateFlow<PlayerNativeState> = combine(observed, palette) { native, (uri, colors) ->
        val queue = graph.queue.state.value
        val currentUri = queue.entries.firstOrNull { it.occurrenceId == native.occurrenceId }?.item?.artworkUri
        native.copy(palette = colors.takeIf { uri == graph.artworkLoader.displayUri(currentUri) }.orEmpty())
    }.stateIn(
        graph.scope,
        SharingStarted.WhileSubscribed(STATE_STOP_TIMEOUT_MS),
        iosNativeState(
            graph.queue.state.value,
            graph.player.snapshot.value,
            graph.settings.snapshots.value,
            handoff = graph.handoff.value,
        ),
    )

    override fun previous() {
        graph.scope.launch { graph.skipPrevious() }
    }

    override fun next() {
        graph.scope.launch { graph.skipNext() }
    }

    override fun setShuffle(enabled: Boolean) {
        graph.scope.launch {
            try {
                if (!graph.queue.setShuffle(enabled)) graph.report("shuffle was not applied: a radio queue keeps server order")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("shuffle was not applied: ${failure::class.simpleName}")
            }
        }
    }

    override fun setRepeat(repeat: PlayerRepeat) {
        val stored = repeat.toQueueRepeatMode().storedValue
        graph.scope.launch {
            try {
                graph.settings.edit { set(RepeatModeKey, stored) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("repeat was not saved: ${failure::class.simpleName}")
            }
        }
    }

    override fun setVolume(volume: Float) {
        graph.setVolume(volume)
    }

    override fun seekStep(forward: Boolean) {
        val playback = graph.player.snapshot.value ?: return
        if (!playback.isSeekable) return
        val progressive = graph.settings.snapshots.value?.get(SeekExtraSeconds) ?: false
        val previous = lastSeekAt
        val repeated = previous != null && previous.elapsedNow().inWholeMilliseconds < SEEK_REPEAT_WINDOW_MS
        seekMultiplier = if (progressive && repeated) seekMultiplier + 1 else 1
        lastSeekAt = clock.markNow()
        val step = SEEK_STEP_MS * seekMultiplier
        val position = graph.player.positionMs() ?: playback.positionMs
        graph.player.seekTo(iosSeekTarget(position, if (forward) step else -step, playback.durationMs ?: 0L))
    }

    override fun onAction(action: PlayerAction, occurrenceId: String?) {
        when (action) {
            // The root closes the sheet for COLLAPSE before an action reaches the host.
            PlayerAction.COLLAPSE -> Unit
            PlayerAction.LIKE -> toggleLike(occurrenceId)
            PlayerAction.SHARE -> share(occurrenceId)
            PlayerAction.SLEEP -> sleepSheet.open = true
            // The more button opens the same song menu a row's overflow shows, tempo and pitch included.
            PlayerAction.MORE -> openItemMenu(occurrenceId)
            PlayerAction.TITLE -> openAlbum(occurrenceId)
            PlayerAction.ARTIST -> openArtist(occurrenceId)
            PlayerAction.OUTPUT -> outputPicker.value = true
            else -> graph.report("player action ${action.name} is not connected on the iOS host")
        }
    }

    /**
     * The Now Playing sheet's Share: presents the system share sheet with the playing song's catalogue link, the iOS
     * counterpart of the desktop copy. A local file or a song with no public link shows the notice that says why
     * instead of opening an empty sheet.
     */
    private fun share(occurrenceId: String?) {
        val item = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return
        graph.scope.launch {
            if (item.ref.provider == ContentProvider.LOCAL.id) {
                try { iosMenuSharing(itemMenu, host, graph).shareLocalAudio(item.ref.id, null) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { itemMenu.notice(IosItemMenuState.NOTICE_SHARE_UNAVAILABLE) }
                return@launch
            }
            val link = iosNowPlayingShareLink(item.ref, host)
            // The sheet and the notice are both UIKit/Compose surfaces, so they run on the main queue.
            withContext(Dispatchers.Main) {
                if (link == null) itemMenu.notice(IosItemMenuState.NOTICE_SHARE_UNAVAILABLE)
                else iosShareText(host, link)
            }
        }
    }

    /** Opens the one row menu on the playing occurrence, which a queue entry already is. */
    private fun openItemMenu(occurrenceId: String?) {
        val entry = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }
        if (entry == null) {
            graph.report("player action ${PlayerAction.MORE.name} has no queue entry on the iOS host")
            return
        }
        itemMenu.open(entry)
    }

    private fun openAlbum(occurrenceId: String?) {
        val entry = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId } ?: return
        val album = entry.item.albumId
        if (!album.isNullOrBlank() && entry.item.ref.provider != ContentProvider.LOCAL.id)
            openRoute(ArchiveRoutes.content(ContentRef(entry.item.ref.provider, ContentKind.ALBUM, album)))
        else itemMenu.open(entry)
    }

    private fun openArtist(occurrenceId: String?) {
        val entry = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId } ?: return
        val choices = entry.item.artists.mapNotNull { artist -> artist.ref(entry.item.ref.provider)?.let { it to artist.name } }.distinctBy { it.first }.take(32)
        if (choices.size == 1) chooseArtist(choices.single().first)
        else if (choices.isNotEmpty()) artistChoices.value = choices
        else itemMenu.open(entry)
    }

    fun chooseArtist(ref: ContentRef) { artistChoices.value = emptyList(); openRoute(ArchiveRoutes.content(ref)) }

    private fun toggleLike(occurrenceId: String?) {
        val item = graph.queue.state.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item ?: return
        graph.scope.launch {
            try {
                val currentlyLiked = graph.libraryContent.isLiked(item.ref).first()
                graph.libraryContent.setLiked(item, !currentlyLiked)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                graph.report("the like was not saved: ${failure::class.simpleName}")
            }
        }
    }
}

/**
 * The observed native state for [playback]. Previous and next follow the queue owner's own rules: the same successor
 * for the repeat mode in settings, and the same restart threshold as the graph's previous skip.
 */
internal fun iosNativeState(
    queue: QueueState,
    playback: PlaybackSnapshot?,
    preferences: PreferenceSnapshot?,
    liked: Boolean = false,
    handoff: QueueHandoffState? = null,
): PlayerNativeState {
    val mode = iosRepeatMode(preferences)
    val current = queue.currentOccurrenceId
    val index = current?.let { queue.playOrder.indexOf(it) } ?: -1
    return PlayerNativeState(
        occurrenceId = playback?.occurrenceId,
        playbackGeneration = playback?.playbackGeneration,
        canPrevious = playback != null && index >= 0 &&
            (index > 0 || playback.positionMs > IosSessionGraph.RESTART_THRESHOLD_MS),
        canNext = playback != null && current != null &&
            QueueAdvancePolicy.successor(mode, queue.playOrder, current) != null,
        canSeek = playback?.isSeekable == true,
        shuffle = queue.shuffleEnabled,
        repeat = mode.toPlayerRepeat(),
        liked = liked,
        volume = preferences?.get(PlayerVolumeKey)?.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f,
        canvasAvailable = false,
        palette = emptyList(),
        handoff = handoff?.takeIf { it.fromOccurrenceId == playback?.occurrenceId }?.let {
            PlayerHandoff(it.fromOccurrenceId, it.toOccurrenceId, it.fadeStartPositionMs, it.overlapMs)
        },
    )
}

/** The seek target the Android player computes: never before zero, and never past a duration the engine reports. */
private fun iosSeekTarget(currentPositionMs: Long, deltaMs: Long, durationMs: Long): Long {
    val current = currentPositionMs.coerceAtLeast(0L)
    val target = (if (deltaMs > 0L && current > Long.MAX_VALUE - deltaMs) Long.MAX_VALUE else current + deltaMs)
        .coerceAtLeast(0L)
    return if (durationMs > 0L) target.coerceAtMost(durationMs) else target
}

private fun QueueRepeatMode.toPlayerRepeat(): PlayerRepeat = when (this) {
    QueueRepeatMode.OFF -> PlayerRepeat.OFF
    QueueRepeatMode.ONE -> PlayerRepeat.ONE
    QueueRepeatMode.ALL -> PlayerRepeat.ALL
}

private fun PlayerRepeat.toQueueRepeatMode(): QueueRepeatMode = when (this) {
    PlayerRepeat.OFF -> QueueRepeatMode.OFF
    PlayerRepeat.ONE -> QueueRepeatMode.ONE
    PlayerRepeat.ALL -> QueueRepeatMode.ALL
}

/**
 * The public catalogue link of [ref] for the share sheet, or null for a local file or a provider that has none. A
 * Telegram id resolves through the signed-in client, which is the only owner of its channel message link. The same
 * provider-to-link rules the desktop share applies, so a Spotify id never becomes a YouTube video id.
 */
private suspend fun iosNowPlayingShareLink(ref: ContentRef, host: IosHost): String? {
    if (ref.id.startsWith("telegram://")) {
        val track = TelegramTrackRef.fromMediaId(ref.id) ?: return null
        return host.telegram.value?.messageLink(track.chatId, track.messageId)
    }
    val id = ref.id.takeIf { id -> id.isNotBlank() && id.none(Char::isWhitespace) } ?: return null
    return when (ref.provider) {
        ContentProvider.YOUTUBE.id -> when (ref.kind) {
            ContentKind.SONG, ContentKind.EPISODE -> "https://music.youtube.com/watch?v=$id"
            ContentKind.ARTIST -> "https://music.youtube.com/channel/$id"
            ContentKind.PLAYLIST -> "https://music.youtube.com/playlist?list=$id"
            ContentKind.ALBUM, ContentKind.PODCAST -> "https://music.youtube.com/browse/$id"
            else -> null
        }
        ContentProvider.SPOTIFY_CATALOG.id -> {
            val type = when (ref.kind) {
                ContentKind.SONG -> "track"
                ContentKind.EPISODE -> "episode"
                ContentKind.ARTIST -> "artist"
                ContentKind.PLAYLIST -> "playlist"
                ContentKind.ALBUM -> "album"
                ContentKind.PODCAST -> "show"
                else -> return null
            }
            "https://open.spotify.com/$type/$id"
        }
        else -> null
    }
}
