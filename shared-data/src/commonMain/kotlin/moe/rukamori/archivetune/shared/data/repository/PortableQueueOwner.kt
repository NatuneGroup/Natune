package moe.rukamori.archivetune.shared.data.repository

import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.shared.data.persistence.AlbumCompletionIndex
import moe.rukamori.archivetune.shared.domain.persistence.QueueCheckpoint
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistCompletion
import moe.rukamori.archivetune.shared.domain.queue.FinitePlaylistPhase
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.queue.QueueAdvancePolicy
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import moe.rukamori.archivetune.shared.domain.queue.ShuffleOrder
import moe.rukamori.archivetune.shared.domain.queue.UserQueuePlayOrder
import moe.rukamori.archivetune.shared.domain.queue.UserQueuePolicy
import moe.rukamori.archivetune.shared.domain.queue.UserQueuedKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.repository.QueueCollectionKind
import moe.rukamori.archivetune.shared.domain.repository.QueueLoadRequest
import moe.rukamori.archivetune.shared.domain.repository.QueueLocatorResolver
import moe.rukamori.archivetune.shared.domain.repository.QueueStart
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.repository.QueueTail
import moe.rukamori.archivetune.shared.domain.repository.TypedQueueOwnerPort

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
    /** Records the complete track list of an album whose whole walk was loaded. Absent, nothing is recorded. */
    albumCompletion: AlbumCompletionIndex? = null,
) : TypedQueueOwnerPort {
    private val lock = Mutex()
    private val finitePlaylists = FinitePlaylistLoader(content, albumCompletion)
    private var inside = false
    private val published = MutableStateFlow(
        QueueState(revision = 0L, entries = emptyList(), currentOccurrenceId = null, playOrder = emptyList()),
    )
    override val state: StateFlow<QueueState> = published.asStateFlow()

    private val advanceFailureFlow = MutableSharedFlow<Throwable>(extraBufferCapacity = 8)

    /** Failures of the automatic end-of-track advance, which has no caller to return them to. */
    val automaticAdvanceFailures: SharedFlow<Throwable> = advanceFailureFlow.asSharedFlow()

    /** The occurrence and generation whose end was already acted on, so one end advances exactly once. */
    private var lastEnded: Pair<String, Long>? = null

    /**
     * Android's consecutive-error score. It rises on every automatic skip after a failure and resets only when the
     * queue pauses or stops, so repeated failures stop the queue even when playback works in between.
     */
    private var failureScore = 0

    // Declared after every field it reads, because the watchers may start before the constructor returns.
    private val endWatcher: Job = scope.launch {
        player.snapshot.collect { snapshot -> onSnapshot(snapshot) }
    }

    private val failureWatcher: Job = scope.launch {
        player.failures.collect { failure -> onFailure(failure) }
    }

    /** Stops watching the player for ends and failures. The owner must not be used afterwards. */
    fun close() {
        endWatcher.cancel()
        failureWatcher.cancel()
    }

    override suspend fun <T> withQueueAccess(operation: suspend () -> T): T = lock.withLock {
        check(!inside) { "Queue access is not reentrant" }
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
        val loaded: List<ContentOccurrence>
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

        published.value = QueueState(
            revision = published.value.revision + 1,
            entries = loaded,
            currentOccurrenceId = current.occurrenceId,
            playOrder = order.map { loaded[it].occurrenceId },
            phase = if (finite) FinitePlaylistPhase.EXHAUSTED else FinitePlaylistPhase.UNKNOWN,
            shuffleEnabled = request.shuffle,
            manualKinds = emptyMap(),
            tail = tail,
        )
        player.load(playRequest, request.positionMs, request.playWhenReady)
    }

    override suspend fun replace(entries: List<ContentOccurrence>, startOccurrenceId: String, playWhenReady: Boolean) {
        requireInside()
        requireUnique(entries)
        val start = entries.firstOrNull { it.occurrenceId == startOccurrenceId }
            ?: throw IllegalArgumentException("Start occurrence is not in the replacement queue")
        val playRequest = requestFor(start)
        val queue = published.value
        published.value = QueueState(
            revision = queue.revision + 1,
            entries = entries,
            currentOccurrenceId = start.occurrenceId,
            playOrder = entries.map { it.occurrenceId },
            phase = FinitePlaylistPhase.UNKNOWN,
            shuffleEnabled = false,
            manualKinds = emptyMap(),
            tail = null,
        )
        player.load(playRequest, 0L, playWhenReady)
    }

    /**
     * Places hand-queued songs with the same rules as the Android queue: Play next behind earlier Play next
     * songs, Add to queue behind every hand-queued song, both ahead of radio and tail songs. With keep-in-order
     * off, Play next goes straight after the current song and Add to queue to the end.
     */
    override suspend fun insert(entries: List<ContentOccurrence>, kind: UserQueuedKind) {
        requireInside()
        if (entries.isEmpty()) return
        val queue = published.value
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
        published.value = queue.copy(
            revision = queue.revision + 1,
            entries = timeline.toList(),
            currentOccurrenceId = queue.currentOccurrenceId ?: becomesCurrent,
            playOrder = nextOrder,
            manualKinds = manualKinds,
        )
        if (playRequest != null) player.load(playRequest, 0L, playWhenReady = false)
    }

    override suspend fun remove(occurrenceIds: Set<String>) {
        requireInside()
        val queue = published.value
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
        published.value = queue.copy(
            revision = queue.revision + 1,
            entries = entries,
            currentOccurrenceId = successor,
            playOrder = playOrder,
            manualKinds = manualKinds,
        )
        when {
            playRequest != null -> player.load(
                playRequest,
                0L,
                playWhenReady = player.snapshot.value?.playWhenReady ?: true,
            )
            successor == null && current != null -> player.stop()
        }
    }

    /**
     * Moves in timeline order. With shuffle off the play order is the timeline, so it follows the move. With
     * shuffle on the play order keeps its own sequence of occurrence ids and only the timeline changes.
     */
    override suspend fun move(occurrenceId: String, beforeOccurrenceId: String?) {
        requireInside()
        val queue = published.value
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
        published.value = queue.copy(
            revision = queue.revision + 1,
            entries = reordered.toList(),
            playOrder = playOrder,
        )
    }

    /**
     * Turns shuffle on or off, as Media3's shuffle mode does on Android: on, the current song leads a fresh shuffle of
     * the whole timeline; off, play order is the timeline again. The current song and the playback are untouched. A queue
     * with a radio tail stays in order, because its next pages are appended in server order; that refusal returns false.
     */
    override suspend fun setShuffle(enabled: Boolean): Boolean {
        requireInside()
        val queue = published.value
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
        published.value = queue.copy(revision = queue.revision + 1, playOrder = playOrder, shuffleEnabled = enabled)
        return true
    }

    override suspend fun select(occurrenceId: String, positionMs: Long) {
        requireInside()
        require(positionMs >= 0) { "Position must not be negative" }
        val queue = published.value
        val target = queue.entries.firstOrNull { it.occurrenceId == occurrenceId }
            ?: throw IllegalArgumentException("Occurrence is not in the queue")
        val playRequest = requestFor(target)
        published.value = queue.copy(revision = queue.revision + 1, currentOccurrenceId = occurrenceId)
        player.load(playRequest, positionMs, playWhenReady = true)
    }

    /**
     * The exact queue, its revision and the current position. The position belongs only to the current
     * occurrence; a snapshot for another occurrence reads as zero rather than a misplaced resume point.
     */
    override suspend fun capture(): QueueCheckpoint {
        requireInside()
        val queue = published.value
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
        published.value = queue
        if (playRequest == null) {
            failureScore = 0
            player.stop()
        } else {
            player.load(playRequest, checkpoint.positionMs, playWhenReady = false)
        }
    }

    override suspend fun raiseRevisionTo(floor: Long) {
        requireInside()
        val queue = published.value
        if (queue.revision < floor) published.value = queue.copy(revision = floor)
    }

    private fun onSnapshot(snapshot: PlaybackSnapshot?) {
        if (snapshot == null || !snapshot.isEnded) return
        val key = snapshot.occurrenceId to snapshot.playbackGeneration
        if (key == lastEnded) return
        lastEnded = key
        scope.launch {
            try {
                withQueueAccess { advanceAfterEnd(snapshot.occurrenceId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                advanceFailureFlow.tryEmit(failure)
            }
        }
    }

    /**
     * Moves to the occurrence that follows the one that ended, under the repeat mode. Repeat-all wraps to the start
     * rather than extending a radio tail, because a repeating queue keeps its own songs.
     */
    private suspend fun advanceAfterEnd(ended: String) {
        val queue = published.value
        if (queue.currentOccurrenceId != ended) return
        val next = nextAfter(queue, ended) ?: return
        select(next, 0L)
    }

    /**
     * The occurrence after [id] under the current repeat mode. With repeat off and a radio tail that has more pages,
     * the next page is appended first. A finite queue with no tail has no successor at its last song.
     */
    private suspend fun nextAfter(queue: QueueState, id: String): String? {
        val mode = repeatMode()
        var next = QueueAdvancePolicy.successor(mode, queue.playOrder, id)
        if (next == null && queue.tail != null && mode == QueueRepeatMode.OFF) {
            extendTail()
            next = QueueAdvancePolicy.successor(mode, published.value.playOrder, id)
        }
        return next
    }

    /**
     * Skips past a failed occurrence when the host allows it. The score rises by one failure step. Past the limit,
     * or with nothing left to play, the queue pauses and the score resets, as on Android. A failure for an
     * occurrence that is no longer current is stale and is ignored.
     */
    private suspend fun skipAfterFailure(failed: String) {
        val queue = published.value
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

    /** Appends the next radio page. Duplicate occurrences from the page are dropped, never repeated. */
    private suspend fun extendTail() {
        val queue = published.value
        val tail = queue.tail ?: return
        val token = tail.continuation ?: return
        check(!queue.shuffleEnabled) { "A radio tail is never shuffled" }
        val page = content.page(tail.collection, token)
        val consumed = tail.consumedTokens + token
        FinitePlaylistCompletion.validateNext(page.continuation, consumed, page.items.size)
        val known = queue.entries.mapTo(HashSet()) { it.occurrenceId }
        val fresh = mutableListOf<ContentOccurrence>()
        for (item in page.items) {
            if (known.add(item.occurrenceId)) fresh += item
        }
        published.value = queue.copy(
            revision = queue.revision + 1,
            entries = queue.entries + fresh,
            playOrder = queue.playOrder + fresh.map { it.occurrenceId },
            tail = page.continuation?.let { QueueTail(tail.collection, it, consumed, tail.kind) },
        )
    }

    private suspend fun requestFor(occurrence: ContentOccurrence): PlaybackRequest =
        PlaybackRequest(occurrence.occurrenceId, occurrence.item.ref.id, locators.locate(occurrence))

    private fun requireInside() = check(inside) { "Queue changes must run inside withQueueAccess" }

    private fun upcomingKinds(queue: QueueState): List<UserQueuedKind> {
        val current = queue.currentOccurrenceId ?: return emptyList()
        val position = queue.playOrder.indexOf(current)
        if (position < 0) return emptyList()
        return queue.playOrder.drop(position + 1)
            .takeWhile { queue.manualKinds.containsKey(it) }
            .map { queue.manualKinds.getValue(it) }
    }

    private fun successorAfterRemoval(playOrder: List<String>, current: String, removed: Set<String>): String? {
        val position = playOrder.indexOf(current)
        if (position < 0) return null
        return playOrder.drop(position + 1).firstOrNull { it !in removed }
            ?: playOrder.take(position).lastOrNull { it !in removed }
    }

    private fun startIndexOf(entries: List<ContentOccurrence>, start: QueueStart?): Int {
        if (start == null) return 0
        val occurrenceId = start.occurrenceId
        if (occurrenceId != null) {
            return entries.indexOfFirst { it.occurrenceId == occurrenceId }
                .takeIf { it >= 0 }
                ?: throw IllegalArgumentException("Start occurrence is not in the collection")
        }
        val sameSong = entries.withIndex().filter { it.value.item.ref == start.item }
        if (sameSong.isEmpty()) throw IllegalArgumentException("Start item is not in the collection")
        val setVideoId = start.setVideoId
        if (setVideoId != null) {
            sameSong.firstOrNull { it.value.setVideoId == setVideoId }?.let { return it.index }
        }
        return sameSong.first().index
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
