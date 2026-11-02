package moe.rukamori.archivetune.shared.domain.presence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Sends heartbeats from playback. While the opt-in is active and [playing] is true, one payload goes out immediately
 * and then once per [intervalMs] plus a uniform jitter of up to [ListenerPresencePolicy.HEARTBEAT_JITTER_MS]. A pause,
 * a stop, an opt-out or an identifier change cancels the loop and sends one best-effort stopped payload. Nothing
 * retries, and no loop runs while idle.
 *
 * Call [start] once from the host's main thread. Without an HTTPS endpoint the transport is unavailable and nothing
 * starts.
 */
class ListenerPresenceController(
    private val scope: CoroutineScope,
    private val store: ListenerPresenceStore,
    private val transport: ListenerPresenceTransport,
    private val playing: StateFlow<Boolean>,
    private val platform: ListenerPresencePlatform,
    private val appVersion: String,
    private val intervalMs: Long = ListenerPresencePolicy.HEARTBEAT_INTERVAL_MS,
) {
    private var job: Job? = null

    fun start() {
        if (job != null || !transport.available) return
        job =
            scope.launch {
                combine(store.state, playing) { state, isPlaying ->
                    val id = state.listenerId
                    if (state.active && isPlaying && id != null && ListenerPresencePolicy.isValidListenerId(id)) id else null
                }
                    .distinctUntilChanged()
                    .collectLatest { id -> if (id != null) beat(id) }
            }
    }

    private suspend fun beat(id: String) {
        try {
            while (true) {
                sendQuietly(ListenerPresencePolicy.payloadJson(id, platform, appVersion, ListenerPresencePolicy.STATE_PLAYING))
                delay(intervalMs + Random.nextLong(0, ListenerPresencePolicy.HEARTBEAT_JITTER_MS))
            }
        } finally {
            withContext(NonCancellable) {
                sendQuietly(ListenerPresencePolicy.payloadJson(id, platform, appVersion, ListenerPresencePolicy.STATE_STOPPED))
            }
        }
    }

    private suspend fun sendQuietly(payloadJson: String) {
        try {
            transport.send(payloadJson)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // Dropped on purpose: the next playback tick sends a fresh payload instead of retrying this one.
        }
    }
}
