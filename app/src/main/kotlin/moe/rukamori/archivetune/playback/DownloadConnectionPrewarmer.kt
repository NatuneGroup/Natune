package moe.rukamori.archivetune.playback

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.audiosource.executeCancellable
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Owns the app-start download-host pre-warm.
 *
 * The pre-warm used to allocate a [CoroutineScope] per call and never cancel it, so every call
 * left a scope and its HEAD probes alive for the process lifetime. This owner keeps one
 * [SupervisorJob] for its whole lifetime instead, ignores a call while a pass is already running
 * (an activity recreation must not fan the probes out), and cancels in-flight probes on [close].
 */
internal class DownloadConnectionPrewarmer(
    private val hosts: List<String>,
    private val probe: suspend (String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(client: OkHttpClient, hosts: List<String>) : this(hosts, headProbe(client), Dispatchers.IO)

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /**
     * Starts one serial probe pass and returns it, or returns null when a pass is already running
     * or this instance is closed. The caller may ignore the result; [close] cancels an in-flight
     * pass, and probes are never retried, so a repeated call cannot become a retry storm.
     */
    fun prewarm(): Job? {
        if (closed.get() || !running.compareAndSet(false, true)) return null
        return scope.launch {
            try {
                for (host in hosts) {
                    currentCoroutineContext().ensureActive()
                    try {
                        probe(host)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Best effort only: one unreachable host must not stop the remaining probes.
                    }
                }
            } finally {
                running.set(false)
            }
        }
    }

    /**
     * Cancels the owned scope and, through the probe's cancellation handler, its HTTP calls, so a
     * shutdown does not wait out OkHttp's read timeout. The instance stays closed afterwards.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        job.cancel()
    }
}

/** HEAD `https://<host>/`; the response is discarded and a failure is left to the pass's catch. */
private fun headProbe(client: OkHttpClient): suspend (String) -> Unit =
    { host ->
        val request =
            Request
                .Builder()
                .url("https://$host/")
                .head()
                .build()
        client.newCall(request).executeCancellable().close()
    }
