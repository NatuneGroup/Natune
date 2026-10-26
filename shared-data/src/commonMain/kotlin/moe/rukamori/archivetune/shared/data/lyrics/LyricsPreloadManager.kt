package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsSentinels
import kotlin.coroutines.coroutineContext

/**
 * Warms lyrics for the tracks that play next, the port of the Android `LyricsPreloadManager`.
 *
 * Preloads go through [helper], so a preload and a foreground lookup for the same track share one
 * provider fetch. At most [maxConcurrent] preloads run at a time. The manager tracks in-flight and
 * successful preloads, and it does not read a database: the host's storage decides what is already
 * stored, and the in-memory record only prevents repeated work in one session.
 *
 * The host drives it with [update] (the upcoming tracks after the current one) and [onSongChanged].
 * [update] is the only call that starts loads. [onSongChanged] only cancels, so a song change does not
 * restart the old window; call [update] with the new upcoming list afterwards.
 */
class LyricsPreloadManager(
    private val helper: LyricsHelper,
    private val scope: CoroutineScope,
    private val nextCount: Int = DEFAULT_NEXT_COUNT,
    private val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
) {
    init {
        require(nextCount >= 0) { "nextCount must not be negative" }
        require(maxConcurrent > 0) { "maxConcurrent must be positive" }
    }

    private val lock = Mutex()
    private val permits = Semaphore(maxConcurrent)
    private val jobs = HashMap<String, Job>()
    private val loaded = LyricsBoundedCache<String, Boolean>(LOADED_CAPACITY)
    private var currentKey: String? = null
    private var closed = false

    /**
     * Makes [upcoming] the window. Only its first [nextCount] entries count, as on Android, and a
     * track already loaded or in flight is skipped. A preload that has left the window is cancelled,
     * unless it belongs to the current song.
     */
    suspend fun update(upcoming: List<LyricsRequest>) {
        lock.withLock {
            if (closed) return
            val window =
                upcoming
                    .distinctBy { it.trackKey }
                    .filterNot { it.trackKey == currentKey }
                    .take(nextCount)
            val keep = window.mapTo(HashSet()) { it.trackKey }
            currentKey?.let { keep += it }
            cancelJobs { it !in keep }

            for (request in window) {
                val key = request.trackKey
                if (key in jobs) continue
                if (loaded.get(key) == true) continue
                start(key, request)
            }
        }
    }

    /**
     * Cancels every in-flight preload except the one for [current], which is the song now playing
     * and is left to finish. Call [update] afterwards to restart the window.
     */
    suspend fun onSongChanged(current: LyricsRequest?) {
        lock.withLock {
            if (closed) return
            currentKey = current?.trackKey
            cancelJobs { it != currentKey }
        }
    }

    /** Cancels every preload. Later [update] and [onSongChanged] calls are ignored. */
    suspend fun close() {
        lock.withLock {
            closed = true
            currentKey = null
            jobs.values.forEach { it.cancel() }
            jobs.clear()
        }
    }

    /** Must be called with [lock] held. The launch is LAZY so registration always precedes the body. */
    private fun start(
        key: String,
        request: LyricsRequest,
    ) {
        val job = scope.launch(start = CoroutineStart.LAZY) { preload(key, request) }
        jobs[key] = job
        job.start()
    }

    /** Must be called with [lock] held. */
    private fun cancelJobs(shouldCancel: (String) -> Boolean) {
        val doomed = jobs.keys.filter(shouldCancel)
        doomed.forEach { key -> jobs.remove(key)?.cancel() }
    }

    private suspend fun preload(
        key: String,
        request: LyricsRequest,
    ) {
        val self = coroutineContext[Job]
        try {
            // A failure here is dropped on purpose: preloads are best-effort, and the helper has already
            // reported any provider failure. A foreground lookup for the track will surface it again.
            val result =
                suspendRunCatching {
                    permits.withPermit { helper.getLyricsWithProvider(request) }
                }.getOrNull()
            if (result != null && result.lyrics != LyricsSentinels.NOT_FOUND) {
                loaded.put(key, true)
            }
        } finally {
            withContext(NonCancellable) {
                lock.withLock {
                    if (jobs[key] === self) jobs.remove(key)
                }
            }
        }
    }

    companion object {
        const val DEFAULT_NEXT_COUNT = 3
        const val DEFAULT_MAX_CONCURRENT = 2

        /** Matches the memo bound used for word-synced upgrades. */
        private const val LOADED_CAPACITY = 64
    }
}
