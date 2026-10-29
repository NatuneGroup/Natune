package moe.rukamori.archivetune.shared.data.repository

import io.ktor.client.plugins.ResponseException
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.persistence.AlbumCompletionIndex
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.playbackCauses
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.queue.ShuffleOrder
import moe.rukamori.archivetune.shared.domain.queue.UserQueuePlayOrder
import moe.rukamori.archivetune.shared.domain.queue.UserQueuePolicy
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.songmetadata.SongMetadataRepository
import moe.rukamori.archivetune.shared.domain.songmetadata.canonicalSong
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.repository.QueueTail
import moe.rukamori.archivetune.shared.domain.repository.TypedQueueOwnerPort
import moe.rukamori.archivetune.shared.domain.repository.radioQueueRequest

/**
 * The one queue owner for the portable (desktop) host. It decides which occurrence is current, what the
 * player is asked to load, and what the queue holds. [state] is the observable projection of that owner,
 * metadata included; there is no second timeline, first-page copy or revision counter elsewhere.
 *
 * Every queue method must run inside [withQueueAccess]. The automatic end-of-track advance and the automatic skip
 * after a failure take the same lock themselves, so neither interleaves with a host command.
 *
 * Repeat and skip-on-error follow the Android rules in [QueueAdvancePolicy]. The host supplies the two settings as
 * readers, so a change applies to the next end or failure without rebuilding the owner.
 *
 * With a [QueueHandoffEngine] the owner also arms crossfade and automix handoffs: ahead of the end of the current song
 * it resolves the successor and asks the engine to blend it in. When the engine commits, the playing occurrence changes
 * without a load and the owner makes the successor current. A seek or pause before the blend starts drops the arm, and
 * the next stretch of playback arms it again.
 */
class PortableQueueOwner(
    override val player: MusicPlayerController,
    private val scope: CoroutineScope,
    private val content: ContentRepositoryRouter,
    private val locators: QueueLocatorResolver,
    /** Mirrors the Android `QueueAddAfterManualKey` setting, which defaults to on. */
    private val keepManualQueueInOrder: Boolean = true,
    private val random: Random = Random.Default,
    /** The repeat mode, read at each end or failure. Defaults to off, as on Android. */
    private val repeatMode: () -> QueueRepeatMode = { QueueRepeatMode.OFF },
    /** Mirrors the Android `AutoSkipNextOnErrorKey` setting. Off stops the queue at a failure, as before. */
    private val autoSkipNextOnError: () -> Boolean = { false },
    /**
     * Mirrors the Android `AutoLoadMoreKey` setting. On, an exhausted finite queue seeds a watch-next tail
     * from the current song (`onInfiniteQueueEnabled`); off, its last song ends the queue.
     */
    private val autoLoadMoreEnabled: () -> Boolean = { true },
    /** Mirrors `HideExplicitKey`: entries the provider marks explicit never reach the queue. */
    private val hideExplicit: () -> Boolean = { false },
    /** Mirrors `HideVideoKey`: entries the provider marks as music videos never reach the queue. */
    private val hideVideo: () -> Boolean = { false },
    /** The provider artist ids the user blocked; entries carrying one never reach the queue. */
    private val blockedArtistIds: () -> Set<String> = { emptySet() },
    /** Records the complete track list of an album whose whole walk was loaded. Absent, nothing is recorded. */
    albumCompletion: AlbumCompletionIndex? = null,
    /** Blends songs into each other. Absent, every song ends before its successor loads, as before. */
    private val handoffEngine: QueueHandoffEngine? = null,
    private val songMetadata: SongMetadataRepository? = null,
) : TypedQueueOwnerPort {
    private val lock = Mutex()
    private val finitePlaylists = FinitePlaylistLoader(content, albumCompletion)
    private var inside = false
    private val publication = QueueMetadataProjection(songMetadata)
    override val state: StateFlow<QueueState> = publication.state
    private var currentState: QueueState
        get() = publication.current
        set(value) {
            publication.current = value
        }

    private val advanceFailureFlow = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)

    /** Failures of the automatic end-of-track advance, which has no caller to return them to. */
    val automaticAdvanceFailures: SharedFlow<Throwable> = advanceFailureFlow.asSharedFlow()

    private val resolutionFailureFlow = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)

    /**
     * The failures the error dialog and skip-on-error act on: the engine's, and those of songs no source resolved. An
     * unresolved song never reaches the engine, so without the second kind it would stop silently.
     */
    val failures: Flow<PlaybackFailure> = merge(player.failures, resolutionFailureFlow)

    /** The occurrence and generation whose end was already acted on, so one end advances exactly once. */
    private var lastEnded: Pair<String, Long>? = null

    /**
     * Whether the queue's current tail lineage is a watch-next radio. A consumed radio tail re-seeds at the
     * queue's end (the original `YouTubeQueue` is not a finite playlist, so `onInfiniteQueueEnabled` re-arms
     * for it once it has no page left); a finite queue seeds only while EXHAUSTED.
     */
    private var radioTailSeeded = false

    /**
     * Android's consecutive-error score. It rises on every automatic skip after a failure and resets only when the
     * queue pauses or stops, so repeated failures stop the queue even when playback works in between.
     */
    private var failureScore = 0

    /** The handoff the engine holds now. Read and replaced from the watchers and the locked queue methods alike. */
    private val armed = MutableStateFlow<ArmedHandoff?>(null)

    /**
     * The newest handoff that was armed, kept after a cancel too: a cancel can cross the engine's commit, and the queue
     * must still follow the song that is audible. A load clears it.
     */
    private val lastHandoff = MutableStateFlow<ArmedHandoff?>(null)

    private val publishedHandoff = MutableStateFlow<QueueHandoffState?>(null)

    /** The audible blend armed from the current song into its successor. Null without one or for a gapless splice. */
    val handoff: StateFlow<QueueHandoffState?> = publishedHandoff.asStateFlow()

    // Declared after every field it reads, because the watchers may start before the constructor returns.
    private val metadataWatcher: Job? = publication.watch(scope) {
        // Same queue, order, revision and audible engine; only its metadata projection changes.
        withQueueAccess { currentState = currentState }
    }
    private val endWatcher: Job = scope.launch {
        player.snapshot.collect { snapshot -> onSnapshot(snapshot) }
    }

    private val failureWatcher: Job = scope.launch {
        failures.collect { failure -> onFailure(failure) }
    }

    private val handoffWatcher: Job? = handoffEngine?.let { engine ->
        scope.launch {
            combine(player.snapshot.map { it?.handoffKey() }.distinctUntilChanged(), engine.configuration) { key, _ -> key }
                .collectLatest { key -> if (key != null) scheduleHandoff(engine, key) }
        }
    }

    /** Stops watching the player for ends and failures. The owner must not be used afterwards. */
    fun close() {
        metadataWatcher?.cancel()
        endWatcher.cancel()
        failureWatcher.cancel()
        handoffWatcher?.cancel()
    }

    /**
     * Drops an armed handoff, so the current song plays to its own end. A host whose engine stops wanting the blend,
     * such as the sleep timer's end-of-song choice, reaches it through [QueueHandoffEngine.configuration].
     */
    fun cancelHandoff() {
        val pending = armed.value ?: return
        if (armed.compareAndSet(pending, null)) {
            publishedHandoff.value = null
            handoffEngine?.cancel()
        }
    }

    override suspend fun <T> withQueueAccess(operation: suspend () -> T): T = lock.withLock {
        check(!inside) { "Queue access is not reentrant" }
        publication.initializeNow()
        inside = true
        try {
            operation()
        } finally {
            inside = false
        }
    }

    /**
     * Loads a whole finite collection, or the first page of a radio collection, then commits it in one step.
     * A finite list is committed only once every page is confirmed. A failure leaves the previous queue as it
     * was, rather than leaving a partial list that looks complete.
     */
    override suspend fun loadCollection(request: QueueLoadRequest) {
        requireInside()
        val collection = request.collection
        val finite = request.kind == QueueCollectionKind.FINITE
        var loaded: List<ContentOccurrence>
        val tail: QueueTail?
        if (finite) {
            loaded = withTimeoutOrNull(FinitePlaylistCompletion.TIMEOUT_MS) {
                finitePlaylists.walk(collection).songs.map { it.item }
            } ?: throw IncompleteFinitePlaylistException("Finite playlist loading timed out")
            tail = null
        } else {
            val first = content.page(collection, null)
            FinitePlaylistCompletion.validateNext(first.continuation, emptySet(), first.items.size, initial = true)
            loaded = first.items
            tail = first.continuation?.let {
                QueueTail(collection = collection, continuation = it, kind = QueueCollectionKind.RADIO)
            }
        }
        // The hidden-content rules of `Queue.Status.filterPlaybackContent` apply to every queue load.
        loaded = loaded.filterPlaybackContent()
        if (loaded.isEmpty()) throw IncompleteFinitePlaylistException("Collection returned no songs")
        requireUnique(loaded)
        radioTailSeeded = false

        val startIndex = startIndexOf(loaded, request.start)
        val order: List<Int> = if (request.shuffle) {
            val shuffled = ShuffleOrder.shuffled(
                size = loaded.size,
                random = random,
                startIndex = if (request.start == null) null else startIndex,
            )
            shuffled.playOrder()
        } else {
            loaded.indices.toList()
        }
        val currentIndex = if (request.shuffle) order.first() else startIndex
        val current = loaded[currentIndex]
        val playRequest = requestFor(current)

        currentState = QueueState(
            revision = currentState.revision + 1,
            entries = loaded,
            currentOccurrenceId = current.occurrenceId,
            playOrder = order.map { loaded[it].occurrenceId },
            phase = if (finite) FinitePlaylistPhase.EXHAUSTED else FinitePlaylistPhase.UNKNOWN,
            shuffleEnabled = request.shuffle,
            manualKinds = emptyMap(),
            tail = tail,
        )
        startPrepared(playRequest, request.positionMs, request.playWhenReady)
        radioTailSeeded = tail?.kind == QueueCollectionKind.RADIO
        // Android seeds radio right after a confirmed-complete finite load (`shouldBootstrapInfiniteQueue`),
        // so the tail pends behind the queue's own songs instead of appearing only at the end.
        if (finite && autoLoadMoreEnabled() && repeatMode() == QueueRepeatMode.OFF) {
            try {
                seedRadioTail()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed seed never fails the load: the queue keeps playing its own songs, as on Android.
            }
        }
    }

    override suspend fun replace(entries: List<ContentOccurrence>, startOccurrenceId: String, playWhenReady: Boolean) {
        requireInside()
        requireUnique(entries)
        val start = entries.firstOrNull { it.occurrenceId == startOccurrenceId }
            ?: throw IllegalArgumentException("Start occurrence is not in the replacement queue")
        val playRequest = requestFor(start)
        val queue = currentState
        currentState = QueueState(
            revision = queue.revision + 1,
            entries = entries,
            currentOccurrenceId = start.occurrenceId,
            playOrder = entries.map { it.occurrenceId },
            phase = FinitePlaylistPhase.UNKNOWN,
            shuffleEnabled = false,
            manualKinds = emptyMap(),
            tail = null,
        )
        startPrepared(playRequest, 0L, playWhenReady)
        radioTailSeeded = false
    }

    /**
     * Places hand-queued songs with the same rules as the Android queue: Play next behind earlier Play next
     * songs, Add to queue behind every hand-queued song, both ahead of radio and tail songs. With keep-in-order
     * off, Play next goes straight after the current song and Add to queue to the end.
     */
    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) {
        requireInside()
        if (entries.isEmpty()) return
        val queue = currentState
        requireNewOccurrences(queue.entries, entries)
        val currentIndex = queue.currentOccurrenceId?.let { id ->
            queue.entries.indexOfFirst { it.occurrenceId == id }.takeIf { it >= 0 }
        }
        val placement = UserQueuePolicy.placement(
            kind = kind,
            count = entries.size,
            keepInOrder = keepManualQueueInOrder,
            itemCount = queue.entries.size,
            currentIndex = currentIndex,
            shuffleEnabled = queue.shuffleEnabled,
            upcoming = { upcomingKinds(queue) },
        ) ?: return

        val newIds = entries.map { it.occurrenceId }
        val timeline = queue.entries.toMutableList().apply { addAll(placement.timelineIndex, entries) }
        val order = queue.playOrder.toMutableList()
        when (val play = placement.playOrder) {
            // Shuffle is off here, so the play order is simply the new timeline; it is rebuilt below.
            UserQueuePlayOrder.Timeline -> Unit
            is UserQueuePlayOrder.AfterCurrent -> {
                val currentId = checkNotNull(queue.currentOccurrenceId) { "Shuffled insertion needs a current song" }
                val position = (order.indexOf(currentId) + 1 + play.skipUpcoming).coerceAtMost(order.size)
                order.addAll(position, newIds)
            }
            UserQueuePlayOrder.RandomPosition -> {
                val floor = queue.currentOccurrenceId?.let { order.indexOf(it) + 1 } ?: 0
                for (id in newIds) order.add(floor + random.nextInt(order.size - floor + 1), id)
            }
        }
        val nextOrder = if (placement.playOrder == UserQueuePlayOrder.Timeline) {
            timeline.map { it.occurrenceId }
        } else {
            order.toList()
        }
        val manualKinds = if (keepManualQueueInOrder) {
            queue.manualKinds + newIds.associateWith { kind }
        } else {
            queue.manualKinds
        }

        // An empty queue has no current song. The first song becomes current paused, as Media3 prepares it.
        val becomesCurrent = if (queue.currentOccurrenceId == null) nextOrder.first() else null
        val playRequest = becomesCurrent?.let { id -> requestFor(timeline.first { it.occurrenceId == id }) }
        currentState = queue.copy(
            revision = queue.revision + 1,
            entries = timeline.toList(),
            currentOccurrenceId = queue.currentOccurrenceId ?: becomesCurrent,
            playOrder = nextOrder,
            manualKinds = manualKinds,
        )
        if (playRequest != null) startPrepared(playRequest, 0L, playWhenReady = false) else revalidateHandoff()
    }

    override suspend fun remove(occurrenceIds: Set<String>) {
        requireInside()
        val queue = currentState
        if (queue.entries.none { it.occurrenceId in occurrenceIds }) return
        val entries = queue.entries.filterNot { it.occurrenceId in occurrenceIds }
        val playOrder = queue.playOrder.filterNot { it in occurrenceIds }
        val manualKinds = queue.manualKinds.filterKeys { it !in occurrenceIds }
        val current = queue.currentOccurrenceId
        val successor = if (current != null && current in occurrenceIds) {
            successorAfterRemoval(queue.playOrder, current, occurrenceIds)
        } else {
            current
        }
        val playRequest = if (successor != null && successor != current) {
            requestFor(entries.first { it.occurrenceId == successor })
        } else {
            null
        }
        currentState = queue.copy(
            revision = queue.revision + 1,
            entries = entries,
            currentOccurrenceId = successor,
            playOrder = playOrder,
            manualKinds = manualKinds,
        )
        when {
            playRequest != null -> startPrepared(
                playRequest,
                0L,
                playWhenReady = player.snapshot.value?.playWhenReady ?: true,
            )
            successor == null && current != null -> {
                forgetHandoff()
                player.stop()
            }
            else -> revalidateHandoff()
        }
    }

    /**
     * Moves in timeline order. With shuffle off the play order is the timeline, so it follows the move. With
     * shuffle on the play order keeps its own sequence of occurrence ids and only the timeline changes.
     */
    override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) {
        requireInside()
        val queue = currentState
        val from = queue.entries.indexOfFirst { it.occurrenceId == occurrenceId }
        require(from >= 0) { "Moved occurrence is not in the queue" }
        require(beforeOccurrenceId != occurrenceId) { "An occurrence cannot move before itself" }
        val reordered = queue.entries.toMutableList()
        val moving = reordered.removeAt(from)
        val to = if (beforeOccurrenceId == null) {
            reordered.size
        } else {
            reordered.indexOfFirst { it.occurrenceId == beforeOccurrenceId }
                .also { require(it >= 0) { "Anchor occurrence is not in the queue" } }
        }
        reordered.add(to, moving)
        val reorderedIds = reordered.map { it.occurrenceId }
        if (reorderedIds == queue.entries.map { it.occurrenceId }) return
        val playOrder = if (queue.shuffleEnabled) queue.playOrder else reorderedIds
        currentState = queue.copy(
            revision = queue.revision + 1,
            entries = reordered.toList(),
            playOrder = playOrder,
        )
        revalidateHandoff()
    }

    /**
     * Turns shuffle on or off, as Media3's shuffle mode does on Android: on, the current song leads a fresh shuffle of
     * the whole timeline; off, play order is the timeline again. The current song and the playback are untouched. A queue
     * with a radio tail stays in order, because its next pages are appended in server order; that refusal returns false.
     */
    override suspend fun setShuffle(enabled: Boolean): Boolean {
        requireInside()
        val queue = currentState
        if (queue.shuffleEnabled == enabled) return true
        if (enabled && queue.tail != null) return false
        val timeline = queue.entries.map { it.occurrenceId }
        val playOrder = if (enabled) {
            timeline.shuffled(random).toMutableList().apply {
                queue.currentOccurrenceId?.let { current ->
                    remove(current)
                    add(0, current)
                }
            }
        } else {
            timeline
        }
        currentState = queue.copy(revision = queue.revision + 1, playOrder = playOrder, shuffleEnabled = enabled)
        revalidateHandoff()
        return true
    }

    /**
     * Android's `MusicService.startRadioSeamlessly`: a watch-next queue seeded by the current song replaces the
     * songs after it. The seed itself is dropped from the radio page. Local and non-YouTube songs return false,
     * as on Android, and the queue is left alone. When the seed page brings nothing new, the songs after the
     * current one stay in place, also as on Android; the radio tail still attaches and keeps paging.
     */
    override suspend fun startRadioSeamlessly(): Boolean {
        requireInside()
        val queue = currentState
        val currentId = queue.currentOccurrenceId ?: return false
        val currentIndex =
            queue.entries.indexOfFirst { it.occurrenceId == currentId }.takeIf { it >= 0 } ?: return false
        val seed = queue.entries[currentIndex].item.ref
        if (seed.provider != ContentProvider.YOUTUBE.id || seed.kind != ContentKind.SONG) return false
        val collection = radioQueueRequest(seed)
        val page = content.page(collection, null)
        val radioItems = page.items.filterPlaybackContent().filter { it.item.ref.id != seed.id }
        val tail =
            page.continuation?.let { QueueTail(collection = collection, continuation = it, kind = QueueCollectionKind.RADIO) }
        if (radioItems.isEmpty()) {
            currentState = queue.copy(revision = queue.revision + 1, phase = FinitePlaylistPhase.UNKNOWN, tail = tail)
            radioTailSeeded = true
            return true
        }
        val kept = queue.entries.subList(0, currentIndex + 1)
        val removedIds =
            queue.entries.subList(currentIndex + 1, queue.entries.size).mapTo(HashSet()) { it.occurrenceId }
        val order = queue.playOrder.filterNot { it in removedIds }.toMutableList()
        val radioIds = radioItems.map { it.occurrenceId }
        if (queue.shuffleEnabled) {
            // Media3's shuffle order spreads appended items across the unplayed remainder, not straight after.
            val floor = order.indexOf(currentId) + 1
            for (id in radioIds) order.add(floor + random.nextInt(order.size - floor + 1), id)
        } else {
            order.addAll(order.indexOf(currentId) + 1, radioIds)
        }
        currentState = queue.copy(
            revision = queue.revision + 1,
            entries = kept + radioItems,
            playOrder = order.toList(),
            phase = FinitePlaylistPhase.UNKNOWN,
            manualKinds = queue.manualKinds.filterKeys { it !in removedIds },
            tail = tail,
        )
        radioTailSeeded = true
        return true
    }

    override suspend fun select(occurrenceId: String, positionMs: Long) {
        requireInside()
        require(positionMs >= 0) { "Position must not be negative" }
        val queue = currentState
        val target = queue.entries.firstOrNull { it.occurrenceId == occurrenceId }
            ?: throw IllegalArgumentException("Occurrence is not in the queue")
        val playRequest = requestFor(target)
        currentState = queue.copy(revision = queue.revision + 1, currentOccurrenceId = occurrenceId)
        startPrepared(playRequest, positionMs, playWhenReady = true)
    }

    /**
     * The exact queue, its revision and the current position. The position belongs only to the current
     * occurrence; a snapshot for another occurrence reads as zero rather than a misplaced resume point.
     */
    override suspend fun capture(): QueueCheckpoint {
        requireInside()
        val queue = currentState
        val current = queue.currentOccurrenceId
        val position = if (current != null && player.snapshot.value?.occurrenceId == current) {
            player.positionMs()?.coerceAtLeast(0L) ?: 0L
        } else {
            0L
        }
        return QueueCheckpoint(queue, position)
    }

    /** Publishes the saved queue with its saved revision, then loads its current song paused at the saved position. */
    override suspend fun restore(checkpoint: QueueCheckpoint) {
        requireInside()
        val queue = checkpoint.queue
        val current = queue.currentOccurrenceId
        val playRequest = current?.let { id -> requestFor(queue.entries.first { it.occurrenceId == id }) }
        currentState = queue
        if (playRequest == null) {
            failureScore = 0
            forgetHandoff()
            player.stop()
        } else {
            startPrepared(playRequest, checkpoint.positionMs, playWhenReady = false)
        }
    }

    override suspend fun raiseRevisionTo(floor: Long) {
        requireInside()
        val queue = currentState
        if (queue.revision < floor) currentState = queue.copy(revision = floor)
    }

    private fun onSnapshot(snapshot: PlaybackSnapshot?) {
        if (snapshot == null) return
        followHandoff(snapshot)
        if (!snapshot.isEnded) return
        val key = snapshot.occurrenceId to snapshot.playbackGeneration
        if (key == lastEnded) return
        lastEnded = key
        val pending = armed.value?.takeIf { it.state.fromOccurrenceId == snapshot.occurrenceId }
        scope.launch {
            try {
                // An armed handoff commits as the outgoing media ends, so the end normally needs no load. The wait
                // covers the poll that reports the commit; without one the end advances as it would have.
                if (pending != null) delay(HANDOFF_COMMIT_GRACE_MS)
                withQueueAccess {
                    if (pending != null && armed.compareAndSet(pending, null)) {
                        publishedHandoff.value = null
                        handoffEngine?.cancel()
                    }
                    advanceAfterEnd(snapshot.occurrenceId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                advanceFailureFlow.tryEmit(failure)
            }
        }
    }

    /**
     * Follows the engine through a handoff. The successor becoming audible makes it current. The outgoing song seeking,
     * pausing or reloading before the blend starts means the engine dropped the arm, so the owner drops it too.
     */
    private fun followHandoff(snapshot: PlaybackSnapshot) {
        val latest = lastHandoff.value
        if (latest != null && snapshot.occurrenceId == latest.state.toOccurrenceId) {
            if (!lastHandoff.compareAndSet(latest, null)) return
            scope.launch {
                try {
                    withQueueAccess { adoptHandoff(latest) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    advanceFailureFlow.tryEmit(failure)
                }
            }
            return
        }
        if (!snapshot.isEnded) dropStaleHandoff(snapshot.handoffKey())
    }

    private fun dropStaleHandoff(key: HandoffKey) {
        val pending = armed.value ?: return
        if (pending.state.fromOccurrenceId != key.occurrenceId) return
        val stale = key.playbackGeneration != pending.playbackGeneration ||
            key.discontinuityGeneration != pending.discontinuityGeneration ||
            !key.playWhenReady
        if (stale && armed.compareAndSet(pending, null)) {
            publishedHandoff.value = null
            handoffEngine?.cancel()
        }
    }

    /** Makes the audible successor current without loading it again, since the engine is already playing it. */
    private fun adoptHandoff(handoff: ArmedHandoff) {
        if (armed.compareAndSet(handoff, null)) publishedHandoff.value = null
        val queue = currentState
        val incoming = handoff.state.toOccurrenceId
        if (queue.currentOccurrenceId != handoff.state.fromOccurrenceId) return
        if (queue.entries.none { it.occurrenceId == incoming }) return
        currentState = queue.copy(revision = queue.revision + 1, currentOccurrenceId = incoming)
    }

    /**
     * Waits until the current song is [HANDOFF_PREPARE_AHEAD_MS] from its blend, resolves the successor outside the
     * queue lock, and arms it if nothing moved meanwhile. Each new playback stretch of the song starts this again.
     */
    private suspend fun scheduleHandoff(engine: QueueHandoffEngine, key: HandoffKey) {
        try {
            dropStaleHandoff(key)
            if (!key.playWhenReady || key.ended) return
            val durationMs = key.durationMs?.takeIf { it > 0L } ?: return
            if (armed.value != null) {
                // A settings change that turns the blend off reaches an arm already made, not only the next song.
                val stillWanted = withQueueAccess { armedStillWanted(engine, key, durationMs) }
                if (!stillWanted) cancelHandoff()
                return
            }
            val plannedOverlapMs = withQueueAccess { plannedHandoff(engine, key, durationMs)?.second } ?: return
            val armAtMs = durationMs - plannedOverlapMs - HANDOFF_PREPARE_AHEAD_MS
            while (true) {
                val positionMs = player.positionMs() ?: return
                val waitMs = ((armAtMs - positionMs) / key.speed.coerceAtLeast(MIN_SPEED)).toLong()
                if (waitMs <= 0L) break
                delay(waitMs)
            }
            val next = withQueueAccess { plannedHandoff(engine, key, durationMs)?.first } ?: return
            // A song no source resolves is left to the ordinary end, which reports the failure where it applies.
            val prepared = requestFor(next) as? Prepared.Ready ?: return
            withQueueAccess { armPlanned(engine, key, durationMs, prepared.request) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            advanceFailureFlow.tryEmit(failure)
        }
    }

    /** The successor of the song [key] plays and the overlap the engine asks for, or null when no handoff applies. */
    private suspend fun plannedHandoff(
        engine: QueueHandoffEngine,
        key: HandoffKey,
        durationMs: Long,
    ): Pair<ContentOccurrence, Long>? {
        val queue = currentState
        if (queue.currentOccurrenceId != key.occurrenceId || armed.value != null) return null
        // Repeat-one and a one-song repeat-all reload the same occurrence, which a second deck cannot carry.
        val nextId = nextAfter(queue, key.occurrenceId)?.takeIf { it != key.occurrenceId } ?: return null
        val entries = currentState.entries
        val current = entries.firstOrNull { it.occurrenceId == key.occurrenceId } ?: return null
        val next = entries.firstOrNull { it.occurrenceId == nextId } ?: return null
        val overlapMs = engine.overlapMs(current, next, durationMs)?.takeIf { it >= 0L } ?: return null
        return next to overlapMs
    }

    private fun armedStillWanted(engine: QueueHandoffEngine, key: HandoffKey, durationMs: Long): Boolean {
        val pending = armed.value ?: return true
        if (pending.state.fromOccurrenceId != key.occurrenceId) return true
        val entries = currentState.entries
        val current = entries.firstOrNull { it.occurrenceId == pending.state.fromOccurrenceId } ?: return false
        val next = entries.firstOrNull { it.occurrenceId == pending.state.toOccurrenceId } ?: return false
        val overlapMs = engine.overlapMs(current, next, durationMs) ?: return false
        return (overlapMs == 0L) == (pending.state.overlapMs == 0L)
    }

    private suspend fun armPlanned(engine: QueueHandoffEngine, key: HandoffKey, durationMs: Long, request: PlaybackRequest) {
        if (player.snapshot.value?.handoffKey() != key) return
        val (next, requestedMs) = plannedHandoff(engine, key, durationMs) ?: return
        if (next.occurrenceId != request.occurrenceId) return
        val positionMs = player.positionMs() ?: return
        val remainingMs = durationMs - positionMs - HANDOFF_END_GUARD_MS
        // A resolution that ran into the blend window shortens the blend rather than skipping it, as on Android.
        val overlapMs = when {
            requestedMs == 0L -> 0L
            remainingMs >= requestedMs -> requestedMs
            remainingMs >= HANDOFF_MIN_OVERLAP_MS -> remainingMs
            else -> return
        }
        if (!engine.arm(request, overlapMs)) return
        val handoff = ArmedHandoff(
            state = QueueHandoffState(key.occurrenceId, next.occurrenceId, durationMs - overlapMs, overlapMs),
            playbackGeneration = key.playbackGeneration,
            discontinuityGeneration = key.discontinuityGeneration,
        )
        armed.value = handoff
        lastHandoff.value = handoff
        publishedHandoff.value = handoff.state.takeIf { overlapMs > 0L }
    }

    /**
     * A queue edit can change the successor under an armed handoff. The arm is dropped then, so the song that ends
     * moves to the successor the queue shows now.
     */
    private fun revalidateHandoff() {
        val pending = armed.value ?: return
        val queue = currentState
        val from = pending.state.fromOccurrenceId
        if (queue.currentOccurrenceId == from &&
            QueueAdvancePolicy.successor(repeatMode(), queue.playOrder, from) == pending.state.toOccurrenceId
        ) {
            return
        }
        cancelHandoff()
    }

    /** A load or stop replaces whatever the engine had armed, so no handoff state outlives it. */
    private fun forgetHandoff() {
        armed.value = null
        lastHandoff.value = null
        publishedHandoff.value = null
    }

    /**
     * Moves to the occurrence that follows the one that ended, under the repeat mode. Repeat-all wraps to the start
     * rather than extending a radio tail, because a repeating queue keeps its own songs.
     */
    private suspend fun advanceAfterEnd(ended: String) {
        val queue = currentState
        if (queue.currentOccurrenceId != ended) return
        val next = nextAfter(queue, ended) ?: return
        select(next, 0L)
    }

    /**
     * The occurrence after [id] under the current repeat mode. With repeat off and a radio tail that has more pages,
     * the next page is appended first. A finite queue whose whole list was confirmed and whose host still wants
     * more music seeds a watch-next tail from the current song, as `onInfiniteQueueEnabled` does with AutoLoadMore
     * on. A queue at any other phase has no successor at its last song: an unknown or failed tail never seeds
     * radio.
     */
    private suspend fun nextAfter(queue: QueueState, id: String): String? {
        val mode = repeatMode()
        var next = QueueAdvancePolicy.successor(mode, queue.playOrder, id)
        if (next == null && mode == QueueRepeatMode.OFF) {
            if (queue.tail != null) {
                extendTail()
            } else if (autoLoadMoreEnabled() &&
                (queue.phase == FinitePlaylistPhase.EXHAUSTED || radioTailSeeded)
            ) {
                seedRadioTail()
            }
            next = QueueAdvancePolicy.successor(mode, currentState.playOrder, id)
        }
        return next
    }

    /**
     * Android's `onInfiniteQueueEnabled`: a confirmed-complete finite queue grows a watch-next tail seeded by the
     * current song. Page items are deduplicated against every song id already queued, never occurrence ids, and the
     * bootstrap keeps reading until a fresh song arrives, bounded by [INFINITE_QUEUE_MAX_BOOTSTRAP_PAGES]. A page
     * that brings nothing new still leaves the radio tail attached, so the queue keeps paging lazily.
     */
    private suspend fun seedRadioTail() {
        val queue = currentState
        val current = queue.entries.firstOrNull { it.occurrenceId == queue.currentOccurrenceId } ?: return
        val seed = current.item.ref
        if (seed.provider != ContentProvider.YOUTUBE.id || seed.kind != ContentKind.SONG) return
        val collection = radioQueueRequest(seed)
        val knownIds = queue.entries.mapTo(HashSet()) { it.item.ref.id }
        val consumed = mutableSetOf<String>()
        var page = content.page(collection, null)
        val fresh =
            page.items.filterPlaybackContent().filterTo(mutableListOf()) { knownIds.add(it.item.ref.id) }
        var continuation = page.continuation
        var pages = 1
        while (fresh.isEmpty() && continuation != null && pages < INFINITE_QUEUE_MAX_BOOTSTRAP_PAGES) {
            pages += 1
            consumed.add(continuation)
            page = content.page(collection, continuation)
            page.items.filterPlaybackContent().filterTo(fresh) { knownIds.add(it.item.ref.id) }
            continuation = page.continuation
        }
        if (fresh.isEmpty() && continuation == null) return
        currentState = queue.copy(
            revision = queue.revision + 1,
            entries = queue.entries + fresh,
            playOrder = queue.playOrder + fresh.map { it.occurrenceId },
            // The queue now runs on the radio queue, so it is no longer the confirmed finite list.
            phase = FinitePlaylistPhase.UNKNOWN,
            tail = continuation?.let { QueueTail(collection, it, consumed, QueueCollectionKind.RADIO) },
        )
        radioTailSeeded = true
    }

    /**
     * Skips past a failed occurrence when the host allows it. The score rises by one failure step. Past the limit,
     * or with nothing left to play, the queue pauses and the score resets, as on Android. A failure for an
     * occurrence that is no longer current is stale and is ignored.
     */
    private suspend fun skipAfterFailure(failed: String) {
        val queue = currentState
        if (queue.currentOccurrenceId != failed) return
        failureScore += QueueAdvancePolicy.FAILURE_SCORE_STEP
        val next =
            if (failureScore <= QueueAdvancePolicy.MAX_FAILURE_SCORE) nextAfter(queue, failed) else null
        if (next == null) {
            failureScore = 0
            player.pause()
            return
        }
        select(next, 0L)
    }

    private fun onFailure(failure: PlaybackFailure) {
        if (!autoSkipNextOnError()) return
        scope.launch {
            try {
                withQueueAccess { skipAfterFailure(failure.occurrenceId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                advanceFailureFlow.tryEmit(error)
            }
        }
    }

    /**
     * Appends the next radio page. Hidden content is dropped before dedupe, and duplicate occurrences from the
     * page are dropped, never repeated, exactly as `onAutoLoadMore` appends a `nextPage`.
     */
    private suspend fun extendTail() {
        val queue = currentState
        val tail = queue.tail ?: return
        val token = tail.continuation ?: return
        check(!queue.shuffleEnabled) { "A radio tail is never shuffled" }
        val page = content.page(tail.collection, token)
        val consumed = tail.consumedTokens + token
        FinitePlaylistCompletion.validateNext(page.continuation, consumed, page.items.size)
        val known = queue.entries.mapTo(HashSet()) { it.occurrenceId }
        val fresh = mutableListOf<ContentOccurrence>()
        for (item in page.items.filterPlaybackContent()) {
            if (known.add(item.occurrenceId)) fresh += item
        }
        currentState = queue.copy(
            revision = queue.revision + 1,
            entries = queue.entries + fresh,
            playOrder = queue.playOrder + fresh.map { it.occurrenceId },
            tail = page.continuation?.let { QueueTail(tail.collection, it, consumed, tail.kind) },
        )
        if (page.continuation == null) radioTailSeeded = tail.kind == QueueCollectionKind.RADIO
    }

    /**
     * Resolves [occurrence] before the queue changes, so the queue is published with the right current song either way.
     * A song no source resolves still becomes current, as a Media3 item that fails to open does on Android: the player
     * stops and the failure goes out with the engine's, rather than the queue command failing with nothing shown.
     */
    private suspend fun requestFor(occurrence: ContentOccurrence): Prepared = try {
        Prepared.Ready(PlaybackRequest(occurrence.occurrenceId, occurrence.item.ref.id, locators.locate(occurrence.copy(item = occurrence.item.canonicalSong()))))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Prepared.Unresolved(
            PlaybackFailure(
                occurrenceId = occurrence.occurrenceId,
                reason = PlaybackFailureReason.SOURCE_UNAVAILABLE,
                message = error.message.orEmpty(),
                httpStatus = error.httpStatusOrNull(),
                causes = error.playbackCauses(),
            ),
        )
    }

    private fun startPrepared(prepared: Prepared, positionMs: Long, playWhenReady: Boolean) {
        forgetHandoff()
        when (prepared) {
            is Prepared.Ready -> player.load(prepared.request, positionMs, playWhenReady)
            is Prepared.Unresolved -> {
                player.stop()
                resolutionFailureFlow.tryEmit(prepared.failure)
            }
        }
    }

    private sealed interface Prepared {
        class Ready(val request: PlaybackRequest) : Prepared
        class Unresolved(val failure: PlaybackFailure) : Prepared
    }

    private data class ArmedHandoff(
        val state: QueueHandoffState,
        val playbackGeneration: Long,
        val discontinuityGeneration: Long,
    )

    /** The parts of a snapshot that start a new playback stretch. Position alone never does. */
    private data class HandoffKey(
        val occurrenceId: String,
        val playbackGeneration: Long,
        val discontinuityGeneration: Long,
        val playWhenReady: Boolean,
        val ended: Boolean,
        val durationMs: Long?,
        val speed: Float,
    )

    private fun PlaybackSnapshot.handoffKey() = HandoffKey(
        occurrenceId = occurrenceId,
        playbackGeneration = playbackGeneration,
        discontinuityGeneration = discontinuityGeneration,
        playWhenReady = playWhenReady,
        ended = isEnded,
        durationMs = durationMs,
        speed = playbackSpeed,
    )

    /** The hidden-content rules of `Queue.Status.filterPlaybackContent`: explicit, video and blocked artists. */
    private fun List<ContentOccurrence>.filterPlaybackContent(): List<ContentOccurrence> {
        val explicit = hideExplicit()
        val video = hideVideo()
        val blocked = blockedArtistIds()
        if (!explicit && !video && blocked.isEmpty()) return this
        return filter { occurrence ->
            (!explicit || !occurrence.item.explicit) &&
                (!video || !occurrence.item.isVideo) &&
                occurrence.item.canonicalSong().artists.none { it.id != null && it.id in blocked }
        }
    }

    private companion object {
        /** Resolve and preload this far ahead of the blend, as Android prepares its second player. */
        const val HANDOFF_PREPARE_AHEAD_MS = 30_000L

        /** The tail no blend may claim, so the outgoing deck never runs dry under it. Android's end guard. */
        const val HANDOFF_END_GUARD_MS = 150L

        /** Android's shortest crossfade. A shorter remainder lets the song end on its own. */
        const val HANDOFF_MIN_OVERLAP_MS = 500L

        /** How long an end waits for the engine to report the commit of an armed handoff. */
        const val HANDOFF_COMMIT_GRACE_MS = 1_500L

        /** Android's bound on the seeding bootstrap: it stops paging after this many empty pages. */
        const val INFINITE_QUEUE_MAX_BOOTSTRAP_PAGES = 3

        const val MIN_SPEED = 0.1f
    }

    private fun requireInside() = check(inside) { "Queue changes must run inside withQueueAccess" }

    private fun upcomingKinds(queue: QueueState): List<UserQueuedKind> {
        val current = queue.currentOccurrenceId ?: return emptyList()
        val position = queue.playOrder.indexOf(current)
        if (position < 0) return emptyList()
        return queue.playOrder.drop(position + 1)
            .takeWhile { queue.manualKinds.containsKey(it) }
            .map { queue.manualKinds.getValue(it) }
    }

    private fun requireUnique(entries: List<ContentOccurrence>) {
        require(entries.mapTo(HashSet()) { it.occurrenceId }.size == entries.size) {
            "Queue occurrence ids must be unique"
        }
    }

    private fun requireNewOccurrences(existing: List<ContentOccurrence>, incoming: List<ContentOccurrence>) {
        requireUnique(incoming)
        val known = existing.mapTo(HashSet()) { it.occurrenceId }
        require(incoming.none { it.occurrenceId in known }) { "Occurrence is already queued" }
    }
}
