package moe.rukamori.archivetune.shared.data.lastfm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiConfig
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmApiException
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmEndpoints
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmErrorCodes
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobble
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmScrobbleStore
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmService
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmSessionPort
import moe.rukamori.archivetune.shared.domain.lastfm.LastFmTrackMetadata

/**
 * Plays that reached the scrobble point but have not been accepted yet.
 *
 * Every scrobble goes through this queue rather than straight to the API, so a play made offline, during an outage or
 * while rate limited is sent later instead of being lost. Entries are written to the host store before the first send
 * attempt, survive process death, and are flushed in batches of [LastFmEndpoints.MAX_SCROBBLES_PER_BATCH] with their
 * original start timestamps. Construct one owner per process and call [flushSoon] at startup, which also loads the
 * stored document. Until then [pendingCount] reads zero.
 *
 * Failures are contained: they go to [onFailure] and never escape into [scope], and cancellation is always rethrown.
 */
class LastFmScrobbleOwner(
    private val scope: CoroutineScope,
    private val service: LastFmService,
    private val sessions: LastFmSessionPort,
    private val store: LastFmScrobbleStore,
    private val config: () -> LastFmApiConfig,
    private val nowEpochSeconds: () -> Long,
    private val onFailure: (Exception) -> Unit = {},
) {
    // Lock order is flushMutex, then stateMutex. enqueue and the store only take stateMutex.
    private val stateMutex = Mutex()
    private val flushMutex = Mutex()

    // Guarded by stateMutex.
    private val entries = ArrayList<PendingLastFmScrobble>()
    private var loaded = false

    // Guarded by flushMutex.
    private var retryJob: Job? = null
    private var retryDelayMs = MIN_RETRY_DELAY_MS

    private val pending = MutableStateFlow(0)

    /** Number of plays waiting to be sent, across every account. */
    val pendingCount: StateFlow<Int> = pending.asStateFlow()

    /** Queues one play that started at [startedAtEpochSeconds]. A play identical to a queued one is ignored. */
    fun enqueue(
        track: LastFmTrackMetadata,
        startedAtEpochSeconds: Long,
    ) {
        launchSafely {
            val session = sessions.currentSession()
            val entry =
                PendingLastFmScrobble(
                    artist = track.artist,
                    track = track.track,
                    timestamp = startedAtEpochSeconds,
                    album = track.album,
                    duration = track.durationSeconds,
                    account = accountScope(config().endpoint, session?.username),
                )
            stateMutex.withLock {
                ensureLoadedLocked()
                val duplicate =
                    entries.any {
                        it.account == entry.account &&
                            it.timestamp == entry.timestamp &&
                            it.artist == entry.artist &&
                            it.track == entry.track
                    }
                if (!duplicate) {
                    entries += entry
                    while (entries.size > MAX_ENTRIES) entries.removeAt(0)
                    persistLocked()
                }
            }
            flush()
        }
    }

    /** Starts a flush in the background. Safe to call at any time, including before the first enqueue. */
    fun flushSoon() {
        launchSafely { flush() }
    }

    /** Call when connectivity returns. A queue that is waiting on a retry is sent at once with the backoff reset. */
    fun onNetworkAvailable() {
        if (pending.value > 0) {
            launchSafely {
                flushMutex.withLock { retryDelayMs = MIN_RETRY_DELAY_MS }
                flush()
            }
        }
    }

    /**
     * Shared by now-playing and love calls, so a revoked session is handled the same way whichever request found it.
     * Only an API error 9 on a known key clears anything, and the host clears it only while it is still that key.
     */
    fun onRequestFailed(
        error: Throwable,
        sessionKeyUsed: String?,
    ) {
        if (error !is LastFmApiException) return
        if (!error.isApiError || error.code != LastFmErrorCodes.INVALID_SESSION) return
        val key = sessionKeyUsed ?: return
        launchSafely { sessions.clearSessionIfCurrent(key) }
    }

    /**
     * Sends the queued plays of the signed-in account in batches, stopping at the first retryable failure. Call it
     * from the host to flush now, for example from the settings "flush pending scrobbles" action.
     */
    suspend fun flush() {
        flushMutex.withLock {
            stateMutex.withLock {
                ensureLoadedLocked()
                pruneExpiredLocked()
            }
            val current = config()
            val session = sessions.currentSession() ?: return
            if (!current.credentials.isUsable || session.sessionKey.isBlank()) return
            val account = accountScope(current.endpoint, session.username)
            var batchLimit = LastFmEndpoints.MAX_SCROBBLES_PER_BATCH
            while (true) {
                val batch =
                    stateMutex.withLock {
                        entries.asSequence().filter { it.account == account }.take(batchLimit).toList()
                    }
                if (batch.isEmpty()) {
                    retryDelayMs = MIN_RETRY_DELAY_MS
                    return
                }
                val result = service.scrobbleBatch(batch.map { it.toScrobble() }, session.sessionKey)
                val failure = result.exceptionOrNull()
                if (failure == null) {
                    remove(batch)
                    retryDelayMs = MIN_RETRY_DELAY_MS
                    continue
                }
                when (classify(failure)) {
                    Failure.DROP -> {
                        // One malformed entry rejects the whole batch, so isolate it instead of discarding the valid
                        // plays that were sent alongside it.
                        if (batch.size > 1) {
                            batchLimit = 1
                        } else {
                            // The malformed play is gone, so the rest of the queue can go back to full batches.
                            remove(batch)
                            batchLimit = LastFmEndpoints.MAX_SCROBBLES_PER_BATCH
                        }
                    }
                    Failure.INVALID_SESSION -> {
                        sessions.clearSessionIfCurrent(session.sessionKey)
                        return
                    }
                    Failure.NO_SESSION -> return
                    Failure.RETRY -> {
                        scheduleRetryLocked()
                        return
                    }
                }
            }
        }
    }

    /** The failure classes the batch loop acts on. Each maps to one baseline rule. */
    private enum class Failure {
        /** Transport, outage, rate limit or a key problem that is not the plays' fault: keep them. */
        RETRY,

        /** Invalid parameters or resource: the entry can never be accepted, so it is dropped. */
        DROP,

        /** The session was revoked: clear it and keep the plays for the same account to sign in again. */
        INVALID_SESSION,

        /** No session key was sent: nothing can be sent until the user signs in. */
        NO_SESSION,
    }

    private fun classify(error: Throwable): Failure {
        if (error !is LastFmApiException) return Failure.RETRY
        if (!error.isApiError) {
            val noSession = error.code == LastFmErrorCodes.INVALID_SESSION && error.httpStatus == null
            return if (noSession) Failure.NO_SESSION else Failure.RETRY
        }
        return when (error.code) {
            LastFmErrorCodes.INVALID_SESSION -> Failure.INVALID_SESSION
            LastFmErrorCodes.INVALID_PARAMETERS, LastFmErrorCodes.INVALID_RESOURCE -> Failure.DROP
            // 11 and 16 (service offline), 29 (rate limit) and 4, 10, 13 and 26 (bad key, signature or suspended
            // key) are not the plays' fault, so they stay queued until the service or the configuration recovers.
            else -> Failure.RETRY
        }
    }

    /** Doubles the backoff after each retryable failure, capped, and retries the flush later. Called under flushMutex. */
    private fun scheduleRetryLocked() {
        retryJob?.cancel()
        val delayMs = retryDelayMs
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
        retryJob =
            launchSafely {
                delay(delayMs)
                flush()
            }
    }

    private suspend fun remove(batch: List<PendingLastFmScrobble>) {
        stateMutex.withLock {
            val sent = batch.toHashSet()
            entries.removeAll { it in sent }
            persistLocked()
        }
    }

    private suspend fun pruneExpiredLocked() {
        // Last.fm ignores timestamps older than 14 days, so keeping them would only grow the document.
        val cutoff = nowEpochSeconds() - MAX_AGE_SECONDS
        if (entries.removeAll { it.timestamp < cutoff }) persistLocked()
    }

    /** Loads the stored document once. A document that cannot be read is discarded, as the baseline did. */
    private suspend fun ensureLoadedLocked() {
        if (loaded) return
        val restored: List<PendingLastFmScrobble> =
            try {
                store.read()?.let { LastFmScrobbleDocument.decode(it) }.orEmpty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
                emptyList()
            }
        loaded = true
        entries.clear()
        entries.addAll(restored)
        pending.value = entries.size
    }

    /**
     * Writes the whole document. A failed write keeps the entries in memory, so the next successful write includes
     * them. Called under stateMutex.
     */
    private suspend fun persistLocked() {
        pending.value = entries.size
        try {
            store.write(LastFmScrobbleDocument.encode(entries.toList()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure(failure)
        }
    }

    /** Runs [block] on the owner's scope. Its failures go to [onFailure] and never reach the host's scope. */
    private fun launchSafely(block: suspend () -> Unit): Job =
        scope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                onFailure(failure)
            }
        }

    /** The queue key: the endpoint and the lowercase username, so accounts never share queued plays. */
    private fun accountScope(
        endpoint: String,
        username: String?,
    ): String = "$endpoint|${username.orEmpty().trim().lowercase()}"

    private fun PendingLastFmScrobble.toScrobble(): LastFmScrobble =
        LastFmScrobble(
            artist = artist,
            track = track,
            timestampEpochSeconds = timestamp,
            album = album,
            durationSeconds = duration,
        )

    private companion object {
        const val MAX_ENTRIES = 2_000
        const val MAX_AGE_SECONDS = 14L * 24 * 60 * 60
        const val MIN_RETRY_DELAY_MS = 30_000L
        const val MAX_RETRY_DELAY_MS = 30 * 60_000L
    }
}
