package moe.rukamori.archivetune.playback

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import timber.log.Timber

/**
 * A `WIFI_MODE_FULL_LOW_LATENCY` lock held while playback runs. Media3's own `C.WAKE_MODE_NETWORK`
 * keeps an ordinary full lock for throughput; Apple Music adds the low-latency mode on top, which
 * asks the radio to keep its low-latency policy for streaming. The mode exists only on API 34+ and
 * only on some radios, so every path degrades to a no-op. Non-refcounted, matching [wakeLock]'s
 * lifecycle in MusicService: acquired while audio plays, released on pause/stop/destroy.
 */
internal class LowLatencyWifiLock(context: Context) {
    private val wifiLock: WifiManager.WifiLock? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            runCatching {
                @Suppress("DEPRECATION")
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, TAG)
                    .apply { setReferenceCounted(false) }
            }.onFailure {
                Timber.tag(TAG).w(it, "Low-latency Wi-Fi lock unavailable")
            }.getOrNull()
        } else {
            null
        }

    /** Idempotent acquire; a no-op where the platform has no low-latency mode. */
    fun acquire() {
        val lock = wifiLock ?: return
        runCatching { if (!lock.isHeld) lock.acquire() }
            .onFailure { Timber.tag(TAG).w(it, "Failed to acquire low-latency Wi-Fi lock") }
    }

    /** Idempotent release. */
    fun release() {
        val lock = wifiLock ?: return
        runCatching { if (lock.isHeld) lock.release() }
            .onFailure { Timber.tag(TAG).w(it, "Failed to release low-latency Wi-Fi lock") }
    }

    private companion object {
        const val TAG = "NatuneWifiLowLatency"
    }
}
