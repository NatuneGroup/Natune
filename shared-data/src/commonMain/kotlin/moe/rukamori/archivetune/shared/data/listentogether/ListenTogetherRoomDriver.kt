package moe.rukamori.archivetune.shared.data.listentogether

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentCredit
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueController

/**
 * Keeps the host player in step with a Listen Together room, as the Android client's
 * listentogether/ListenTogetherManager.kt does. One driver runs beside one [ListenTogetherClient] on the
 * same serial scope; the hosts (desktop and iOS) wire their player, queue and volume port the same way.
 *
 * As host, a track change sends the track and the upcoming queue, play and pause send the position, a seek
 * sends the new position, a heartbeat repeats the position while playing, and a member who joins receives
 * the current track. As guest, a host track is loaded paused, reported ready once the engine has it, and
 * started when the server says every member is ready; play, pause and seek follow the host within the
 * baseline tolerances. A sync state bypasses the buffer wait, as it does on Android.
 *
 * Only YouTube songs travel between members, because a room track is a YouTube video id on every client.
 */
class ListenTogetherRoomDriver(
    private val scope: CoroutineScope,
    private val client: ListenTogetherClient,
    private val player: MusicPlayerController,
    private val queue: QueueController,
    private val setVolume: (Float) -> Unit,
    /** Smart Resync preference: a reconnecting guest asks for a fresh state after the socket settles. */
    private val smartResync: () -> Boolean = { true },
    /** Sync Volume preference: a guest follows host volume changes only while this is on. */
    private val syncVolume: () -> Boolean = { false },
    /** Short reasons for dropped data and failed room work; never raw payloads. */
    private val report: (String) -> Unit = {},
    private val nowMs: () -> Long,
    private val makeOccurrenceId: () -> String = { "listen:${Random.nextLong().toULong().toString(16)}" },
) {
    // Host-side observation of the player.
    private var hostOccurrenceId: String? = null
    private var hostPlayWhenReady: Boolean? = null
    private var hostDiscontinuity: Long? = null

    // Guest-side state: the room track loaded into the local queue and the host state that waits for the buffer.
    private var guestTrackId: String? = null
    private var guestOccurrenceId: String? = null
    private var pendingTrackId: String? = null
    private var pendingPlaying = false
    private var pendingPositionMs = 0L
    private var pendingReceivedAtMs = 0L
    private var lastSyncActionTimeMs = 0L

    /** Starts the engine: client events, host-side player observation, and the play heartbeat. */
    fun start() {
        scope.launch { client.events.collect { event -> guarded("event") { onEvent(event) } } }
        scope.launch { player.snapshot.collect { snapshot -> guarded("host observation") { observeAsHost(snapshot) } } }
        scope.launch {
            while (isActive) {
                delay(ListenTogetherSync.HOST_HEARTBEAT_INTERVAL_MS)
                guarded("heartbeat") {
                    val snapshot = player.snapshot.value
                    if (isHostInRoom() && snapshot?.playWhenReady == true && hostOccurrenceId == snapshot.occurrenceId) {
                        sendAction(PlaybackActions.PLAY, snapshot)
                    }
                }
            }
        }
    }

    private suspend fun onEvent(event: ListenTogetherEvent) {
        when (event) {
            is ListenTogetherEvent.RoomCreated -> {
                reset()
                announceCurrentTrack()
            }
            is ListenTogetherEvent.JoinApproved -> {
                reset()
                applySyncState(event.state.toSyncState())
            }
            is ListenTogetherEvent.Kicked -> reset()
            is ListenTogetherEvent.Reconnected -> {
                reset()
                if (event.isHost) {
                    announceCurrentTrack()
                } else {
                    applySyncState(event.state.toSyncState())
                    // Smart Resync: the reconnect state is what the server last saw, so a guest asks for the
                    // room's current state once the socket settles, as the Android manager does.
                    if (smartResync()) {
                        scope.launch {
                            delay(ListenTogetherSync.SMART_RESYNC_DELAY_MS)
                            if (isGuestInRoom()) client.requestSync()
                        }
                    }
                }
            }
            is ListenTogetherEvent.UserJoined -> if (isHostInRoom()) announceCurrentTrack()
            is ListenTogetherEvent.HostChanged -> {
                reset()
                if (isHostInRoom()) announceCurrentTrack() else client.requestSync()
            }
            is ListenTogetherEvent.PlaybackSync -> if (isGuestInRoom()) applyAction(event.action)
            is ListenTogetherEvent.BufferComplete -> if (isGuestInRoom() && event.trackId == pendingTrackId) applyPending()
            is ListenTogetherEvent.SyncStateReceived -> if (isGuestInRoom()) applySyncState(event.state)
            is ListenTogetherEvent.LocalSuggestionApproved ->
                queueSuggestion(event.suggestion.suggestionId, event.suggestion.trackInfo, event.playImmediately)
            ListenTogetherEvent.RoomLeft, ListenTogetherEvent.Disconnected -> reset()
            else -> Unit
        }
    }

    /** Forgets the host and guest sync state; a room exit or a new connection starts clean. */
    fun reset() {
        lastSyncActionTimeMs = 0L
        hostOccurrenceId = null
        hostPlayWhenReady = null
        hostDiscontinuity = null
        guestTrackId = null
        guestOccurrenceId = null
        pendingTrackId = null
    }

    private fun isHostInRoom(): Boolean =
        client.role.value == ListenTogetherRole.HOST && client.roomState.value != null

    private fun isGuestInRoom(): Boolean =
        client.role.value == ListenTogetherRole.GUEST && client.roomState.value != null

    // Host side

    private fun observeAsHost(snapshot: PlaybackSnapshot?) {
        if (snapshot == null || !isHostInRoom()) return
        when {
            snapshot.occurrenceId != hostOccurrenceId -> announce(snapshot)
            snapshot.playWhenReady != hostPlayWhenReady -> {
                hostPlayWhenReady = snapshot.playWhenReady
                hostDiscontinuity = snapshot.discontinuityGeneration
                sendAction(if (snapshot.playWhenReady) PlaybackActions.PLAY else PlaybackActions.PAUSE, snapshot)
            }
            snapshot.discontinuityGeneration != hostDiscontinuity -> {
                hostDiscontinuity = snapshot.discontinuityGeneration
                sendAction(PlaybackActions.SEEK, snapshot)
            }
        }
    }

    private fun announceCurrentTrack() {
        if (!isHostInRoom()) return
        player.snapshot.value?.let(::announce)
    }

    private fun announce(snapshot: PlaybackSnapshot) {
        hostOccurrenceId = snapshot.occurrenceId
        hostPlayWhenReady = snapshot.playWhenReady
        hostDiscontinuity = snapshot.discontinuityGeneration
        val state = queue.state.value
        val current = state.entries.firstOrNull { it.occurrenceId == snapshot.occurrenceId } ?: return
        val track = current.item.toTrackInfo(snapshot.durationMs) ?: run {
            report("Listen Together: the current track is not a YouTube song, so the room keeps its previous track")
            return
        }
        val order = state.playOrder
        val upcoming = order.drop(order.indexOf(current.occurrenceId) + 1)
            .mapNotNull { id -> state.entries.firstOrNull { it.occurrenceId == id }?.item?.toTrackInfo(null) }
            .take(ListenTogetherSync.MAX_SHARED_QUEUE)
        val position = player.positionMs() ?: snapshot.positionMs
        client.sendPlaybackAction(
            PlaybackActionPayload(
                action = PlaybackActions.CHANGE_TRACK,
                trackId = track.id,
                position = position,
                trackInfo = track,
                queue = upcoming,
            ),
        )
        if (snapshot.playWhenReady) sendAction(PlaybackActions.PLAY, snapshot)
    }

    private fun sendAction(action: String, snapshot: PlaybackSnapshot) {
        val item = queue.state.value.entries.firstOrNull { it.occurrenceId == snapshot.occurrenceId }?.item ?: return
        if (item.ref.provider != ContentProvider.YOUTUBE.id) return
        client.sendPlaybackAction(
            PlaybackActionPayload(action = action, trackId = item.ref.id, position = player.positionMs() ?: snapshot.positionMs),
        )
    }

    private suspend fun queueSuggestion(suggestionId: String, track: TrackInfo, playImmediately: Boolean) {
        val occurrence = track.toOccurrence("listen-suggestion:$suggestionId")
        if (playImmediately) {
            queue.insert(listOf(occurrence), UserQueuedKind.PLAY_NEXT)
            queue.select(occurrence.occurrenceId)
        } else {
            queue.insert(listOf(occurrence), UserQueuedKind.ADD_TO_QUEUE)
        }
    }

    // Guest side

    private suspend fun applyAction(action: PlaybackActionPayload) {
        val now = nowMs()
        when (action.action) {
            PlaybackActions.CHANGE_TRACK -> {
                val track = action.trackInfo ?: return
                loadRoomTrack(track, action.queue.orEmpty())
                pendingTrackId = track.id
                pendingPlaying = false
                pendingPositionMs = action.position ?: 0L
                pendingReceivedAtMs = now
                reportReadyWhenLoaded(track.id)
            }
            PlaybackActions.PLAY -> {
                // Protobuf has no presence for position, so an absent value reads as 0, as on Android.
                val base = action.position ?: 0L
                val target = ListenTogetherSync.adjustedPlayPosition(base, action.serverTime, now)
                if (action.trackId != null && action.trackId != guestTrackId) {
                    client.requestSync()
                    return
                }
                if (pendingTrackId != null) {
                    pendingPlaying = true
                    pendingPositionMs = target
                    pendingReceivedAtMs = now
                    return
                }
                val position = player.positionMs() ?: 0L
                val alreadyPlaying = player.snapshot.value?.playWhenReady == true
                if (alreadyPlaying &&
                    kotlin.math.abs(position - target) < ListenTogetherSync.POSITION_TOLERANCE_MS &&
                    now - lastSyncActionTimeMs < ListenTogetherSync.SYNC_DEBOUNCE_THRESHOLD_MS
                ) {
                    return
                }
                // While playing only a large drift justifies interrupting the audio; paused members align tighter.
                alignPosition(
                    target,
                    ListenTogetherSync.toleranceFor(willPlay = true, isPlayingNow = alreadyPlaying),
                )
                if (!alreadyPlaying) player.play()
                lastSyncActionTimeMs = now
            }
            PlaybackActions.PAUSE -> {
                val position = action.position ?: 0L
                if (pendingTrackId != null) {
                    pendingPlaying = false
                    pendingPositionMs = position
                    pendingReceivedAtMs = now
                    return
                }
                val alreadyPaused = player.snapshot.value?.playWhenReady != true
                if (alreadyPaused &&
                    kotlin.math.abs((player.positionMs() ?: 0L) - position) < ListenTogetherSync.POSITION_TOLERANCE_MS &&
                    now - lastSyncActionTimeMs < ListenTogetherSync.SYNC_DEBOUNCE_THRESHOLD_MS
                ) {
                    return
                }
                if (player.snapshot.value?.playWhenReady == true) player.pause()
                alignPosition(position, ListenTogetherSync.POSITION_TOLERANCE_MS)
                lastSyncActionTimeMs = now
            }
            PlaybackActions.SEEK -> {
                val position = action.position ?: 0L
                if (pendingTrackId != null) {
                    pendingPositionMs = position
                    pendingReceivedAtMs = now
                    return
                }
                if (now - lastSyncActionTimeMs < ListenTogetherSync.SYNC_DEBOUNCE_THRESHOLD_MS) return
                val current = player.positionMs() ?: 0L
                if (kotlin.math.abs(current - position) > ListenTogetherSync.POSITION_TOLERANCE_MS) {
                    player.seekTo(position)
                    lastSyncActionTimeMs = now
                }
            }
            PlaybackActions.SET_VOLUME -> {
                val volume = action.volume ?: return
                if (syncVolume()) setVolume(volume)
            }
            // Skips arrive as a track change, and the room queue is shown from the room state.
            else -> Unit
        }
    }

    private suspend fun applySyncState(state: SyncStatePayload) {
        val track = state.currentTrack ?: return
        val now = nowMs()
        if (track.id != guestTrackId) {
            loadRoomTrack(track, state.queue.orEmpty())
            awaitLoaded()
        }
        pendingTrackId = null
        val position = ListenTogetherSync.adjustedSyncPosition(state.position, state.isPlaying, state.lastUpdate, now)
        player.seekTo(position.coerceAtLeast(0L))
        if (state.isPlaying) player.play() else player.pause()
    }

    private fun applyPending() {
        val position = ListenTogetherSync.adjustedPendingPosition(
            pendingPositionMs,
            pendingPlaying,
            pendingReceivedAtMs,
            nowMs(),
        )
        pendingTrackId = null
        player.seekTo(position.coerceAtLeast(0L))
        if (pendingPlaying) player.play() else player.pause()
    }

    private suspend fun loadRoomTrack(track: TrackInfo, upcoming: List<TrackInfo>) {
        val current = track.toOccurrence(makeOccurrenceId())
        val entries = listOf(current) + upcoming.filter { it.id != track.id }.map { it.toOccurrence(makeOccurrenceId()) }
        guestTrackId = track.id
        guestOccurrenceId = current.occurrenceId
        queue.replace(entries, current.occurrenceId, playWhenReady = false)
    }

    private suspend fun awaitLoaded(): Boolean {
        val occurrenceId = guestOccurrenceId ?: return false
        return withTimeoutOrNull(ListenTogetherSync.BUFFER_WAIT_MS) {
            player.snapshot.first { it != null && it.occurrenceId == occurrenceId && !it.isBuffering }
        } != null
    }

    /** Tells the server this member has the track. A load that never settles still reports, so the room is not held. */
    private fun reportReadyWhenLoaded(trackId: String) {
        scope.launch {
            guarded("buffer wait") {
                if (!awaitLoaded()) report("Listen Together: the room track did not load in time")
                if (pendingTrackId == trackId) client.sendBufferReady(trackId)
            }
        }
    }

    private fun alignPosition(target: Long, tolerance: Long) {
        if (ListenTogetherSync.outsideTolerance(player.positionMs(), target, tolerance)) {
            player.seekTo(target.coerceAtLeast(0L))
        }
    }

    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            report("Listen Together $what failed: ${failure::class.simpleName}")
        }
    }
}

private fun RoomState.toSyncState(): SyncStatePayload =
    SyncStatePayload(currentTrack, isPlaying, position, lastUpdate, queue, volume)

/** The room form of a queue item, or null when the item is not a YouTube song another member could play. */
private fun ContentItem.toTrackInfo(fallbackDurationMs: Long?): TrackInfo? {
    if (ref.provider != ContentProvider.YOUTUBE.id || ref.kind != ContentKind.SONG) return null
    return TrackInfo(
        id = ref.id,
        title = title,
        artist = artists.joinToString(", ") { it.name }.ifBlank { subtitle },
        album = album,
        duration = durationMs ?: fallbackDurationMs ?: 0L,
        thumbnail = artworkUri,
    )
}

/** A room track as a fresh queue occurrence; the queue refuses an id it already holds, so each load gets its own. */
private fun TrackInfo.toOccurrence(occurrenceId: String): ContentOccurrence =
    ContentOccurrence(
        occurrenceId,
        ContentItem(
            ref = ContentRef(ContentProvider.YOUTUBE.id, ContentKind.SONG, id),
            title = title.ifBlank { id },
            subtitle = artist,
            artworkUri = thumbnail?.takeIf { it.isNotBlank() },
            durationMs = duration.takeIf { it > 0L },
            album = album?.takeIf { it.isNotBlank() },
            artists = artist.takeIf { it.isNotBlank() }?.let { listOf(ContentCredit(it)) }.orEmpty(),
        ),
    )
