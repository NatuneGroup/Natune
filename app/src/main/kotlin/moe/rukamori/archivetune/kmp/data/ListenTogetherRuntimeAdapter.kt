/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.rukamori.archivetune.shared.data.listentogether.ListenTogetherGzip
import timber.log.Timber

private const val TAG = "ListenTogetherRuntime"

/**
 * The GZIP adapter for the Listen Together codec, which compresses protobuf frames for the protobuf-only server. When
 * [decompress] returns null, the codec keeps the received bytes as they are, so an unreadable stream drops nothing and
 * never ends the connection.
 */
class AndroidListenTogetherGzip : ListenTogetherGzip {
    override fun compress(data: ByteArray): ByteArray {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { gzip -> gzip.write(data) }
        return buffer.toByteArray()
    }

    override fun decompress(data: ByteArray): ByteArray? =
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { gzip -> gzip.readBytes() }
        } catch (unreadable: IOException) {
            null
        }
}

/**
 * Network availability as the shared client's `network` flow: true while the active network has internet and is
 * validated. It registers its own callback. The existing [moe.rukamori.archivetune.utils.NetworkConnectivityObserver]
 * exposes one single-consumer channel, so sharing it would split the events between two collectors.
 *
 * Call [close] when the client that uses [available] is discarded. Registration failure assumes a connection, as the
 * existing observer does, so a client is never blocked waiting for a signal that cannot arrive.
 */
class ListenTogetherNetworkState(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _available = MutableStateFlow(isConnected())

    /** True while a validated internet connection is active. Updated from the platform callback thread. */
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _available.value = isConnected()
        }

        override fun onLost(network: Network) {
            _available.value = isConnected()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            // Validation arrives here after onAvailable, so the state is read again on every capability change.
            _available.value = isConnected()
        }
    }

    init {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, callback)
        } catch (failed: Exception) {
            Timber.tag(TAG).w(failed, "Network callback not registered; assuming a connection")
            _available.value = true
        }
    }

    /** Stops the platform callback. Safe to call more than once. */
    fun close() {
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun isConnected(): Boolean =
        try {
            val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            if (capabilities == null) {
                false
            } else {
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        } catch (failed: Exception) {
            false
        }
}
