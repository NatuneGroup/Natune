package moe.rukamori.archivetune.playback

import androidx.media3.common.PlaybackException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** A failed connection can precede Android's connectivity callback. Cached playback does not clear it. */
internal class AutoOfflineAvailability(private val connected: () -> Boolean) {
    @Volatile private var connectionFailed = false
    private var lastReportedOffline = !connected()

    fun isOffline(): Boolean = connectionFailed || !connected()

    fun onConnectionFailure(): Boolean {
        connectionFailed = true
        return changed()
    }

    fun onConnectivityChanged(isConnected: Boolean): Boolean {
        if (isConnected) connectionFailed = false
        return changed()
    }

    private fun changed(): Boolean {
        val offline = isOffline()
        return (offline != lastReportedOffline).also { lastReportedOffline = offline }
    }
}

/** Bounded cause inspection also handles errors wrapped by extractor/resolving data sources. */
internal fun Throwable.isNetworkPlaybackFailure(): Boolean = generateSequence(this) { it.cause }.take(16).any {
    it is UnknownHostException || it is ConnectException || it is NoRouteToHostException || it is SocketTimeoutException ||
        it is PlaybackException && (it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            it.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
}
