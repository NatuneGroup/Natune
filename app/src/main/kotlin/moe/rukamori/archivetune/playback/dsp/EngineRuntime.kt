/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b/ArchiveTune (GPL-3.0)
 */

package moe.rukamori.archivetune.playback.dsp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * What the output route currently reports, for the diagnostic surfaces.
 *
 * Every publisher bumps [revision], so a Compose reader keys on that one value instead of polling.
 * The retired engine selections are gone: [dspActiveState] is the one chain's own engagement.
 */
object EngineRuntime {
    @Volatile
    var usbExclusiveActive: Boolean = false

    /** Rate and sample width of the exclusive AAudio stream (the HAL's input, not the USB wire). */
    @Volatile
    var usbExclusiveRateHz: Int = 0

    @Volatile
    var usbExclusiveBits: Int = 0

    var revision by mutableStateOf(0)
        private set

    /** True while [UnifiedDspAudioProcessor] is configured onto the sink and not bypassed. */
    var dspActiveState by mutableStateOf(false)
        private set

    fun publishDspActive(active: Boolean) {
        if (dspActiveState == active) return
        dspActiveState = active
        revision++
    }

    fun publishUsbExclusive(active: Boolean, rateHz: Int = 0, bits: Int = 0) {
        usbExclusiveActive = active
        usbExclusiveRateHz = if (active) rateHz else 0
        usbExclusiveBits = if (active) bits else 0
        revision++
    }
}
