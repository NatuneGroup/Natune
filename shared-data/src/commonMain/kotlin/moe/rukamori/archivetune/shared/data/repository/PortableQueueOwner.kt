package moe.rukamori.archivetune.shared.data.repository

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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import moe.rukamori.archivetune.shared.data.persistence.AlbumCompletionIndex
import moe.rukamori.archivetune.shared.data.repository.QueueStreamRecovery.Prepared
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueDedupePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueEditPolicy
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
import moe.rukamori.archivetune.shared.domain.repository.ContentRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueEditingCommands
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
    /** The `preventDuplicateTracksInQueue` setting; the insert path applies [QueueDedupePolicy]. */
    private val preventDuplicateTracksInQueue: () -> Boolean = { false },
    /** The queueLocked setting: user removes, moves and clears are refused; adds and programmatic edits are not. */
    private val queueLocked: () -> Boolean = { false },
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
    /** Durable host preference changes also cancel an in-flight append when the queue UI is closed. */
    autoLoadMoreChanges: Flow<Boolean> = emptyFlow(),
    monotonicTimeMs: () -> Long = queueMonotonicClock(),
    /** The wall clock for locator expiresAtEpochMs: an epoch instant, never the monotonic clock's ticks. */
    epochNowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : TypedQueueOwnerPort, QueueEditingCommands {
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
    private val source = MutableStateFlow<ContentRequest?>(null)
    override val queueSource: StateFlow<ContentRequest?> = source.asStateFlow()

    private val advanceFailureFlow = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)

    /** Failures of the automatic end-of-track advance, which has no caller to return them to. */
    val automaticAdvanceFailures: SharedFlow<Throwable> = advanceFailureFlow.asSharedFlow()

    private val resolutionFailureFlow = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)
    private val finalFailureFlow = MutableSharedFlow<PlaybackFailure>(extraBufferCapacity = 8)
    private val streamRecovery = QueueStreamRecovery(player, locators, monotonicTimeMs, epochNowMs)

    /**
     * The failures the error dialog and skip-on-error act on: the engine's, and those of songs no source resolved. An
     * unresolved song never reaches the engine, so without the second kind it would stop silently.
     */
    val failures: Flow<PlaybackFailure> = finalFailureFlow.asSharedFlow()

    /** The occurrence and generation whose end was already acted on, so one end advances exactly once. */
    private var lastEnded: Pair<String, Long>? = null

    private val removals = QueueRemovalHistory()
    private val infinite = PortableInfiniteQueue(
        scope = scope,
        access = { operation -> withQueueAccess { operation() } },
        state = state,
        commit = { currentState = it; revalidateHandoff() },
        content = content,
        filter = { it.filterPlaybackContent() },
        enabled = autoLoadMoreEnabled,
        repeatOff = { repeatMode() == QueueRepeatMode.OFF },
        nowMs = monotonicTimeMs,
        report = { advanceFailureFlow.tryEmit(it) },
    )
    override val infiniteQueueLoading: StateFlow<Boolean> = infinite.loading.asStateFlow()

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
        merge(player.failures, resolutionFailureFlow).collect { failure -> onFailure(failure) }
    }

    private val infinitePreferenceWatcher: Job = scope.launch {
        autoLoadMoreChanges.distinctUntilChanged().collect { enabled ->
            if (enabled) infinite.consider() else infinite.cancel()
        }
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
        infinitePreferenceWatcher.cancel()
        infinite.cancel()
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
            tail = QueueTail(collection = collection, continuation = first.continuation, kind = QueueCollectionKind.RADIO)
        }
        // The hidden-content rules of `Queue.Status.filterPlaybackContent` apply to every queue load.
        loaded = loaded.filterPlaybackContent()
        if (loaded.isEmpty()) throw IncompleteFinitePlaylistException("Collection returned no songs")
        requireUnique(loaded)

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

        removals.reset()
        infinite.reset(radio = !finite)
        source.value = collection
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
        // Short queues prefetch immediately; a long finite queue keeps its original membership until near its end.
        if (finite) infinite.replenish()
    }

    override suspend fun replace(entries: List<ContentOccurrence>, startOccurrenceId: String, playWhenReady: Boolean) {
        requireInside()
        requireUnique(entries)
        val start = entries.firstOrNull { it.occurrenceId == startOccurrenceId }
            ?: throw IllegalArgumentException("Start occurrence is not in the replacement queue")
        val playRequest = requestFor(start)
        val queue = currentState
        removals.reset()
        infinite.reset(staticQueue = true)
        source.value = null
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
        infinite.consider()
    }

    /**
     * Places hand-queued songs with the same rules as the Android queue: Play next behind earlier Play next
     * songs, Add to queue behind every hand-queued song, both ahead of radio and tail songs. With keep-in-order
     * off, Play next goes straight after the current song and Add to queue to the end.
     */
    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind): Int {
        requireInside()
        if (entries.isEmpty()) return 0
        var queue = currentState
        var batch = entries
        if (preventDuplicateTracksInQueue()) {
            // An emptied queue takes the same rules: the batch dedupes itself and the copy that
            // survives becomes current. A copy of the song playing is dropped and an earlier
            // occurrence of an incoming media reference is evicted, so it never appears twice.
            val evicted = QueueDedupePolicy.evictable(queue, batch)
            if (evicted.isNotEmpty()) queue = QueueDedupePolicy.evict(queue, evicted)
            batch = QueueDedupePolicy.incoming(queue, batch)
            if (batch.isEmpty()) {
                if (evicted.isNotEmpty()) {
                    currentState = queue.copy(revision = queue.revision + 1)
                    revalidateHandoff()
                }
                return 0
            }
        }
        requireNewOccurrences(queue.entries, batch)
        val currentIndex = queue.currentOccurrenceId?.let { id ->
            queue.entries.indexOfFirst { it.occurrenceId == id }.takeIf { it >= 0 }
        }
        val placement = UserQueuePolicy.placement(
            kind = kind,
            count = batch.size,
            keepInOrder = keepManualQueueInOrder,
            itemCount = queue.entries.size,
            currentIndex = currentIndex,
            shuffleEnabled = queue.shuffleEnabled,
            upcoming = { upcomingKinds(queue) },
        ) ?: return 0

        val newIds = batch.map { it.occurrenceId }
        val timeline = queue.entries.toMutableList().apply { addAll(placement.timelineIndex, batch) }
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
        return batch.size
    }

    override suspend fun remove(occurrenceIds: Set<String>) {
        requireInside()
        if (queueLocked()) return
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
        infinite.consider()
    }

    override suspend fun removeUndoable(occurrenceIds: Set<String>): Long? {
        requireInside()
        if (queueLocked()) return null
        val ticket = removals.remember(currentState, occurrenceIds) ?: return null
        remove(occurrenceIds)
        return ticket
    }

    override suspend fun undoRemoval(ticket: Long): Boolean {
        requireInside()
        val previous = currentState
        val restored = removals.restore(ticket, previous) ?: return false
        val newCurrent = restored.currentOccurrenceId
        val request = if (previous.currentOccurrenceId == null && newCurrent != null) {
            requestFor(restored.entries.first { it.occurrenceId == newCurrent })
        } else null
        currentState = restored
        if (request != null) startPrepared(request, 0L, playWhenReady = false) else revalidateHandoff()
        return true
    }

    override suspend fun clearUpcoming(): Long? {
        requireInside()
        if (queueLocked()) return null
        check(!autoLoadMoreEnabled()) { "Disable infinite queue before clearing it" }
        infinite.reset(staticQueue = true)
        val ticket = removeUndoable(currentState.entries
            .filterNot { it.occurrenceId == currentState.currentOccurrenceId }
            .mapTo(HashSet()) { it.occurrenceId })
        val queue = currentState
        currentState = queue.copy(revision = queue.revision + 1, tail = null, phase = FinitePlaylistPhase.UNKNOWN)
        return ticket
    }

    override suspend fun refreshInfiniteQueue() {
        requireInside()
        if (autoLoadMoreEnabled()) infinite.consider() else infinite.cancel()
    }

    override suspend fun moveNext(occurrenceId: String) {
        requireInside()
        if (queueLocked()) return
        currentState = QueueEditPolicy.moveNext(currentState, occurrenceId)
        revalidateHandoff()
    }

    override suspend fun moveInPlayOrder(occurrenceId: String, beforeOccurrenceId: String?) {
        requireInside()
        if (queueLocked()) return
        currentState = QueueEditPolicy.moveInPlayOrder(currentState, occurrenceId, beforeOccurrenceId)
        revalidateHandoff()
    }

    /**
     * Moves in timeline order. With shuffle off the play order is the timeline, so it follows the move. With
     * shuffle on the play order keeps its own sequence of occurrence ids and only the timeline changes.
     */
    override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) {
        requireInside()
        if (queueLocked()) return
        currentState = QueueEditPolicy.moveInTimeline(currentState, occurrenceId, beforeOccurrenceId)
        revalidateHandoff()
    }

    /** Shuffle edits only the loaded play order, never finite source completeness or the current song. */
    override suspend fun setShuffle(enabled: Boolean): Boolean {
        requireInside()
        currentState = QueueEditPolicy.shuffle(currentState, enabled, random) ?: return false
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
        val kept = queue.entries.subList(0, currentIndex + 1)
        val knownOccurrences = kept.mapTo(HashSet()) { it.occurrenceId }
        val radioItems = moe.rukamori.archivetune.shared.domain.queue.InfiniteQueuePolicy.fresh(
            page.items.filterPlaybackContent(), kept.map { it.item.ref },
        ) { it.item.ref }.filter { knownOccurrences.add(it.occurrenceId) }
        val tail = QueueTail(collection = collection, continuation = page.continuation, kind = QueueCollectionKind.RADIO)
        if (radioItems.isEmpty()) {
            currentState = queue.copy(revision = queue.revision + 1, phase = FinitePlaylistPhase.UNKNOWN, tail = tail)
            infinite.reset(radio = true)
            removals.reset()
            source.value = collection
            return true
        }
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
        infinite.reset(radio = true)
        removals.reset()
        source.value = collection
        return true
    }

    override suspend fun select(occurrenceId: String, positionMs: Long) {
        selectOccurrence(occurrenceId, positionMs, playWhenReady = true)
    }

    /**
     * A manual Next or Previous. The chosen occurrence keeps the play state the player has now, so a paused queue
     * loads paused and a playing one keeps playing. A song tap goes through [select] and always starts playback.
     * Without a player snapshot there is no paused state to keep, so the skip starts playback as it always did.
     */
    suspend fun skipTo(occurrenceId: String, positionMs: Long) {
        selectOccurrence(occurrenceId, positionMs, playWhenReady = player.snapshot.value?.playWhenReady ?: true)
    }

    private suspend fun selectOccurrence(occurrenceId: String, positionMs: Long, playWhenReady: Boolean) {
        requireInside()
        require(positionMs >= 0) { "Position must not be negative" }
        val queue = currentState
        val target = queue.entries.firstOrNull { it.occurrenceId == occurrenceId }
            ?: throw IllegalArgumentException("Occurrence is not in the queue")
        val playRequest = requestFor(target)
        currentState = queue.copy(revision = queue.revision + 1, currentOccurrenceId = occurrenceId)
        startPrepared(playRequest, positionMs, playWhenReady = playWhenReady)
        infinite.consider()
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
        removals.reset()
        // An unknown legacy checkpoint is not proof of a completed finite source.
        infinite.reset(radio = queue.tail?.kind == QueueCollectionKind.RADIO)
        source.value = queue.tail?.collection
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
        if (!snapshot.isEnded && snapshot.playWhenReady) infinite.consider()
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
        streamRecovery.loaded(handoff.request, 0L, player.snapshot.value?.playWhenReady ?: true)
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
        val attempted = streamRecovery.nextAttempt(request)
        if (!engine.arm(attempted, overlapMs)) return
        val handoff = ArmedHandoff(
            request = attempted,
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
            if (queue.tail?.kind == QueueCollectionKind.FINITE) {
                extendTail()
            } else {
                infinite.replenish()
            }
            next = QueueAdvancePolicy.successor(mode, currentState.playOrder, id)
        }
        return next
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

    private suspend fun onFailure(failure: PlaybackFailure) {
        var reported = false
        try {
            withQueueAccess {
                if (failure.occurrenceId != currentState.currentOccurrenceId || !streamRecovery.accepts(failure)) {
                    return@withQueueAccess
                }
                val occurrence = currentState.entries.firstOrNull {
                    it.occurrenceId == failure.occurrenceId && it.occurrenceId == currentState.currentOccurrenceId
                }
                val recovered = occurrence?.let {
                    streamRecovery.recover(failure, it.copy(item = it.item.canonicalSong()))
                }
                if (recovered != null) {
                    startPrepared(Prepared.Ready(recovered.request), recovered.positionMs, recovered.playWhenReady, recovering = true)
                    return@withQueueAccess
                }
                reported = finalFailureFlow.tryEmit(failure) || reported
                if (autoSkipNextOnError()) skipAfterFailure(failure.occurrenceId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // A skip that throws must not report the same terminal failure a second time.
            if (!reported) finalFailureFlow.tryEmit(failure)
            advanceFailureFlow.tryEmit(error)
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
    }

    /**
     * Resolves [occurrence] before the queue changes, so the queue is published with the right current song either way.
     * A song no source resolves still becomes current, as a Media3 item that fails to open does on Android: the player
     * stops and the failure goes out with the engine's, rather than the queue command failing with nothing shown.
     */
    private suspend fun requestFor(occurrence: ContentOccurrence): Prepared = streamRecovery.prepare(occurrence)

    private fun startPrepared(prepared: Prepared, positionMs: Long, playWhenReady: Boolean, recovering: Boolean = false) {
        forgetHandoff()
        when (prepared) {
            is Prepared.Ready -> {
                val attempted = streamRecovery.nextAttempt(prepared.request)
                streamRecovery.loaded(attempted, positionMs, playWhenReady, recovering)
                player.load(attempted, positionMs, playWhenReady)
            }
            is Prepared.Unresolved -> {
                streamRecovery.unresolved()
                player.stop()
                resolutionFailureFlow.tryEmit(prepared.failure)
            }
        }
    }

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
