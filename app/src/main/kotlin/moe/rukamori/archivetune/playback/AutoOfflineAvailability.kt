package moe.rukamori.archivetune.playback

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * A failed connection can precede Android's connectivity callback, and a provider timeout can
 * happen on a validated network, so a failure marks Auto offline only for [failureWindowMs].
 * Cached playback does not clear it; a network success, a connectivity callback or an explicit
 * user retry on a validated network does.
 */
internal class AutoOfflineAvailability(
    private val connected: () -> Boolean,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val failureWindowMs: Long = FAILURE_WINDOW_MS,
    private val scheduleExpiry: (delayMs: Long, action: () -> Unit) -> Unit = { _, _ -> },
    private val onChanged: () -> Unit = {},
) {
    @Volatile private var failedAtMs: Long? = null
    private var lastReportedOffline = !connected()

    fun isOffline(): Boolean = failedAtMs?.let { nowMs() - it < failureWindowMs } == true || !connected()

    fun onConnectionFailure(): Boolean {
        val failedAt = nowMs()
        failedAtMs = failedAt
        scheduleExpiry(failureWindowMs) { if (failedAtMs == failedAt) refresh() }
        return refresh()
    }

    fun onNetworkSuccess(): Boolean {
        if (failedAtMs == null) return false
        failedAtMs = null
        return refresh()
    }

    /** A new search or tap may go online again once Android reports a validated network. */
    fun onUserRetry(): Boolean {
        if (failedAtMs != null && connected()) failedAtMs = null
        return refresh()
    }

    fun onConnectivityChanged(isConnected: Boolean): Boolean {
        if (isConnected) failedAtMs = null
        return refresh()
    }

    /** Reports, once, each change of the effective state, including a silently expired failure. */
    fun refresh(): Boolean {
        val changed = synchronized(this) {
            val offline = isOffline()
            (offline != lastReportedOffline).also { lastReportedOffline = offline }
        }
        if (changed) onChanged()
        return changed
    }

    companion object {
        const val FAILURE_WINDOW_MS = 30_000L
    }
}

/** Bounded cause inspection also handles errors wrapped by extractor/resolving data sources. */
internal fun Throwable.isNetworkPlaybackFailure(): Boolean = generateSequence(this) { it.cause }.take(16).any {
    it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException ||
        it is PlaybackException && (it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
}
