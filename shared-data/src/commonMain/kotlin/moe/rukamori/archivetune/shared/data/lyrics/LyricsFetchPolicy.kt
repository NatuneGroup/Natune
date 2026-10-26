/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.sync.Semaphore

/** The Android LyricsHelper limits, kept as one table so the orchestrator and its tests agree. */
internal object LyricsFetchPolicy {
    const val MAX_CONCURRENT_PROVIDERS = 6

    /**
     * Process-wide, not per helper: the Android comment is explicit that per-instance caps summed
     * past the socket budget. Every provider call holds one permit, including calls made by
     * preloads and foreground lookups alike.
     */
    val providerPermits: Semaphore = Semaphore(MAX_CONCURRENT_PROVIDERS)

    /** Per-provider cap for the normal priority flow and for the all-results fan-out. */
    const val FAN_OUT_TIMEOUT_MS = 8_000L

    /** Longer cap for the word-synced sweep: YouLyPlus alone can take 10-15 s across its mirrors. */
    const val WORD_SYNCED_TIMEOUT_MS = 15_000L

    const val RESULT_CACHE_CAPACITY = 16

    /** One entry per track whose stored row a word-synced upgrade has already settled. */
    const val WORD_SYNCED_UPGRADE_MEMO_CAPACITY = 64
}
