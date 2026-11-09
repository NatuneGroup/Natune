package moe.rukamori.archivetune.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import moe.rukamori.archivetune.constants.PersistentQueueKey
import moe.rukamori.archivetune.models.PersistPlayerState
import moe.rukamori.archivetune.models.PersistQueue
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import timber.log.Timber

private const val TAG = "QueueTeardownPersistence"

/** A queue/player-state pair read on the main thread, with its generation and snapshot order. */
internal class PersistedQueueSnapshot(
    val checkpoint: QueueCheckpointToken,
    val queue: PersistQueue,
    val playerState: PersistPlayerState,
)

/** The write a teardown started, and how long teardown may wait for it. */
internal class TeardownQueueSave(val write: Job, val waitBudgetMs: Long)

// One writer for the whole process, deliberately independent of any service's scopeJob: onDestroy
// cancels that job while the snapshot it has just taken can still be on its way to disk, and a
// write cancelled there is exactly the queue a user left behind. The queue file itself stays safe
// until the rename lands, so a process killed mid-write keeps the queue it already had.
private val teardownPersistenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * The last-chance queue save from `onTaskRemoved` and `onDestroy`, without the unbounded
 * `runBlocking` those callbacks used to hold the main thread with until the whole queue had been
 * written.
 *
 * The snapshot still has to be taken here, on the main thread that owns the player and is about to
 * release it; only the file write is asynchronous. Never throws: a snapshot failure is reported and
 * teardown continues without the save.
 */
internal fun MusicService.saveQueueForTeardown(): TeardownQueueSave? {
    val snapshot = runCatching {
        val enabled = dataStore.get(PersistentQueueKey, true)
        if (TeardownQueuePersistence.shouldSave(enabled, player.mediaItemCount, hasQueueTimelineChanges)) {
            snapshotQueue()
        } else {
            null
        }
    }.onFailure {
        Timber.tag(TAG).w(it, "Could not snapshot the queue during teardown")
    }.getOrNull() ?: return null

    val write = teardownPersistenceScope.launch {
        runCatching { writeQueueSnapshot(snapshot) }
            .onFailure { Timber.tag(TAG).w(it, "Queue save failed during teardown") }
    }
    val waitBudgetMs = TeardownQueuePersistence.destroyWaitMillis(snapshot.queue.items.size.coerceAtLeast(1))
    return TeardownQueueSave(write, waitBudgetMs)
}

/**
 * Waits out whatever is left of [save] at the end of teardown, when everything else is already
 * released. A write that finished while teardown was running — the usual case, since it was
 * handed off tens of milliseconds earlier — costs nothing here.
 *
 * A write that misses its budget is reported rather than swallowed: [Job.join] only cancels the
 * waiter, so the writer still finishes it and the previous queue file remains readable until then.
 */
internal fun awaitTeardownQueueSave(save: TeardownQueueSave?) {
    if (save == null || save.write.isCompleted) return
    if (TeardownQueuePersistence.blockUntilFinished(save.write, save.waitBudgetMs)) return
    Timber.tag(TAG).w("Queue save still running after ${save.waitBudgetMs}ms of teardown")
}

/**
 * What a teardown may spend waiting for the queue file. Both service teardown callbacks run on the
 * main thread, and this file is all that remembers the user's queue, so the two bounds it defines
 * are the whole trade: `onTaskRemoved` does not wait at all (the service keeps running), and
 * `onDestroy` waits a budget that grows with the queue but stops at [DESTROY_WAIT_MAX_MS] however
 * long the queue is.
 */
internal object TeardownQueuePersistence {
    /** Long enough for a normal queue's rename, short enough not to be why teardown lingers. */
    const val DESTROY_WAIT_BASE_MS = 120L

    /** Serialising one more item costs well under a millisecond; this only shapes the budget. */
    const val DESTROY_WAIT_PER_ITEM_MS = 1L

    /** The ceiling: a teardown wait never exceeds this, whatever the queue size. */
    const val DESTROY_WAIT_MAX_MS = 500L

    /** Accepted clears need a checkpoint too; an untouched empty player must preserve saved data. */
    fun shouldSave(
        persistentQueueEnabled: Boolean,
        mediaItemCount: Int,
        hasQueueTimelineChanges: Boolean = false,
    ): Boolean = persistentQueueEnabled && (mediaItemCount > 0 || hasQueueTimelineChanges)

    /** Longer queues serialize longer, up to the ceiling that keeps teardown's wait bounded. */
    fun destroyWaitMillis(mediaItemCount: Int): Long {
        if (mediaItemCount <= 0) return 0L
        val budget = DESTROY_WAIT_BASE_MS + mediaItemCount.toLong() * DESTROY_WAIT_PER_ITEM_MS
        return budget.coerceAtMost(DESTROY_WAIT_MAX_MS)
    }

    /**
     * Blocks the calling thread until [write] finishes, for at most [timeoutMs]. A timeout leaves
     * [write] running: it is the waiter that is cancelled, never the write.
     */
    fun blockUntilFinished(write: Job, timeoutMs: Long): Boolean {
        if (write.isCompleted) return true
        if (timeoutMs <= 0L) return false
        return runBlocking { withTimeoutOrNull(timeoutMs) { write.join() } } != null
    }
}
