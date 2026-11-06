/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 * Portions © 4nx3b — github.com/4nx3b/ArchiveTune (GPL-3.0)
 */

package moe.rukamori.archivetune.playback.dsp

/**
 * Whether the native audio library ships on this device.
 *
 * All it still answers is availability: the library holds the AAudio exclusive output stream, and the
 * USB-exclusive route is offered only when it loads. The 32-bit float chain that used to live in the
 * same library is gone — the shared DSP chain does that work — so there is nothing left to declare here.
 */
object FloatDsp {
    val available: Boolean = runCatching { System.loadLibrary("archivetune_dsp") }.isSuccess
}
