package moe.rukamori.archivetune.desktop.potoken

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Lifecycle of the optional local helper. Technical details are sanitized: no token or cookie ever reaches it. */
internal sealed interface BgutilSidecarStatus {
    data object Stopped : BgutilSidecarStatus

    data object Disabled : BgutilSidecarStatus

    data object Starting : BgutilSidecarStatus

    data class Running(val baseUrl: String, val port: Int) : BgutilSidecarStatus

    data class Failed(val reason: String) : BgutilSidecarStatus
}

/**
 * Owns the pinned helper process: it resolves the verified binary, starts it on a free loopback port, waits for
 * `/ping`, restarts it after a crash with exponential backoff, and stops it on app exit. A start is coalesced so
 * several resolvers asking at once share one download and one process, and a failed attempt keeps its backoff
 * deadline so a doomed helper is not respawned on every track.
 *
 * The bootstrap runs in [scope], not in the caller's job: a playback tier that times out or is cancelled does not
 * abort a download another resolve still needs.
 */
internal class BgutilSidecarManager(
    private val scope: CoroutineScope,
    private val installRoot: Path,
    private val spec: BgutilSidecarSpec?,
    private val enabled: () -> Boolean,
    private val installer: BgutilSidecarInstaller,
    private val transport: BgutilSidecarTransport,
    private val launcher: BgutilProcessLauncher,
    private val nextPort: () -> Int = { preferredOrFreeLoopbackPort(BgutilSidecarRelease.PREFERRED_PORT) },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val supervise: Boolean = true,
    private val log: (String) -> Unit = {},
) {
    private val mutableStatus = MutableStateFlow<BgutilSidecarStatus>(BgutilSidecarStatus.Stopped)
    val status: StateFlow<BgutilSidecarStatus> = mutableStatus.asStateFlow()

    private val lifecycle = Mutex()

    @Volatile
    private var process: BgutilProcessHandle? = null

    @Volatile
    private var baseUrl: String? = null
    private var bootstrap: Deferred<Boolean>? = null
    private var supervisor: Job? = null
    private var failures = 0
    private var nextAttemptAtMs = 0L

    @Volatile
    private var closed = false

    /** True when this host has a pinned asset; the settings row and the provider capability both read it. */
    val supported: Boolean get() = spec != null

    /**
     * The running base URL, starting the helper first when needed. Null means the helper is disabled, unsupported,
     * still inside its failure backoff, or could not become healthy before [waitMs]; callers then fall back cleanly.
     */
    suspend fun ensureRunning(waitMs: Long = START_WAIT_MS): String? {
        if (closed) return null
        if (!enabled()) {
            disable()
            return null
        }
        if (spec == null) {
            mutableStatus.value = BgutilSidecarStatus.Failed(UNSUPPORTED_REASON)
            return null
        }
        currentBaseUrl()?.let { return it }
        if (nowMs() < nextAttemptAtMs) return null

        val task =
            lifecycle.withLock {
                currentBaseUrl()?.let { return@withLock null }
                if (nowMs() < nextAttemptAtMs) return@withLock null
                val pending = bootstrap
                if (pending != null && !pending.isCompleted) {
                    pending
                } else {
                    scope.async { runBootstrap() }.also { bootstrap = it }
                }
            } ?: return currentBaseUrl()

        val ok =
            try {
                withTimeoutOrNull(waitMs) { task.await() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        if (task.isCompleted) {
            lifecycle.withLock {
                if (bootstrap === task) bootstrap = null
            }
        }
        return if (ok == true) currentBaseUrl() else null
    }

    /** Stops a running helper after the user turns the switch off; the next mint stays untokened. */
    suspend fun disable() {
        lifecycle.withLock { stopProcessLocked() }
        baseUrl = null
        mutableStatus.value = BgutilSidecarStatus.Disabled
    }

    /** Stops the process and cancels supervision; called by the session graph's close before the scope is cancelled. */
    suspend fun close() {
        closed = true
        supervisor?.cancel()
        supervisor = null
        bootstrap?.cancel()
        bootstrap = null
        lifecycle.withLock { stopProcessLocked() }
        baseUrl = null
        mutableStatus.value = BgutilSidecarStatus.Stopped
    }

    private fun currentBaseUrl(): String? {
        val handle = process
        if (handle != null && handle.isAlive && baseUrl != null && mutableStatus.value is BgutilSidecarStatus.Running) {
            return baseUrl
        }
        if (handle != null && !handle.isAlive) {
            log("PO token helper exited unexpectedly")
            stopProcessLocked()
            baseUrl = null
            mutableStatus.value = BgutilSidecarStatus.Stopped
        }
        return null
    }

    private suspend fun runBootstrap(): Boolean {
        if (closed) return false
        if (!enabled()) {
            disable()
            return false
        }
        val assetSpec = spec ?: return fail(UNSUPPORTED_REASON)
        mutableStatus.value = BgutilSidecarStatus.Starting
        log("PO token helper starting")

        val binary = installer.resolve(assetSpec) ?: return fail(DOWNLOAD_REASON)
        if (closed) return false
        val port = nextPort()
        val url = "http://${BgutilSidecarRelease.HOST}:$port"
        val handle =
            try {
                launcher.launch(
                    command =
                        listOf(
                            binary.toString(),
                            "server",
                            "--host",
                            BgutilSidecarRelease.HOST,
                            "--port",
                            port.toString(),
                        ),
                    workingDirectory = installRoot,
                    logFile = installRoot.resolve(LOG_FILE_NAME),
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return fail("helper launch failed: ${failure.javaClass.simpleName}")
            }
        process = handle

        val deadline = nowMs() + HEALTH_TIMEOUT_MS
        while (!closed && nowMs() <= deadline) {
            if (!handle.isAlive) {
                stopProcessLocked()
                return fail("helper exited during startup")
            }
            if (transport.ping(url)) {
                baseUrl = url
                failures = 0
                nextAttemptAtMs = 0L
                mutableStatus.value = BgutilSidecarStatus.Running(baseUrl = url, port = port)
                startSupervision()
                log("PO token helper ready on port $port")
                return true
            }
            pause(HEALTH_POLL_MS)
        }
        stopProcessLocked()
        return fail("helper did not answer its health check")
    }

    private fun startSupervision() {
        if (!supervise || supervisor != null || closed) return
        supervisor =
            scope.launch {
                while (isActive && !closed) {
                    pause(SUPERVISION_INTERVAL_MS)
                    if (closed) break
                    val handle = process
                    if (mutableStatus.value is BgutilSidecarStatus.Running && (handle == null || !handle.isAlive)) {
                        log("PO token helper crashed; restarting after backoff")
                        stopProcessLocked()
                        baseUrl = null
                        mutableStatus.value = BgutilSidecarStatus.Starting
                        failures += 1
                        val backoff = backoffMs(failures)
                        nextAttemptAtMs = nowMs() + backoff
                        pause(backoff)
                        // Re-enter the coalesced start path so a concurrent resolve shares this restart instead of
                        // racing a second process against it.
                        if (!closed) ensureRunning()
                    }
                }
            }
    }

    private fun fail(reason: String): Boolean {
        failures += 1
        val backoff = backoffMs(failures)
        nextAttemptAtMs = nowMs() + backoff
        mutableStatus.value = BgutilSidecarStatus.Failed(reason)
        log("PO token helper unavailable: $reason (retry in ${backoff}ms)")
        return false
    }

    private fun backoffMs(failureCount: Int): Long {
        val exponent = (failureCount - 1).coerceIn(0, MAX_BACKOFF_EXPONENT)
        return (BASE_BACKOFF_MS shl exponent).coerceAtMost(MAX_BACKOFF_MS)
    }

    private fun stopProcessLocked() {
        val handle = process ?: return
        process = null
        try {
            handle.destroy()
        } catch (_: Exception) {
        }
    }

    internal companion object {
        const val START_WAIT_MS = 25_000L
        const val HEALTH_TIMEOUT_MS = 20_000L
        const val HEALTH_POLL_MS = 250L
        const val SUPERVISION_INTERVAL_MS = 2_000L
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
        const val MAX_BACKOFF_EXPONENT = 5
        const val LOG_FILE_NAME = "bgutil-pot-provider.log"
        const val UNSUPPORTED_REASON = "no pinned helper binary for this platform"
        const val DOWNLOAD_REASON = "helper download or checksum verification failed"
    }
}

/** Prefers the upstream default port when it is free, otherwise takes an ephemeral loopback port. */
internal fun preferredOrFreeLoopbackPort(preferred: Int): Int {
    val loopback = InetAddress.getByName(BgutilSidecarRelease.HOST)
    return try {
        ServerSocket(preferred, 1, loopback).use { preferred }
    } catch (_: IOException) {
        ServerSocket(0, 1, loopback).use { it.localPort }
    }
}
