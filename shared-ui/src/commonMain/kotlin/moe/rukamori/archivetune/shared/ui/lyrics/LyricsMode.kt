/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.lyrics

/**
 * The fullscreen lyric looks. The names match the values Android stores, so a persisted choice carries over
 * unchanged. Persistence belongs to the settings layer; this type only parses the value it is given.
 */
enum class LyricsMode {
    V2,
    ENHANCED,
    SPOTIFY,
    SIMPMUSIC;

    companion object {
        /** A missing or unknown value falls back to [ENHANCED], the Android default. */
        fun fromStored(value: String?): LyricsMode =
            entries.firstOrNull { it.name == value?.trim() } ?: ENHANCED
    }
}

/**
 * The tuning one shared renderer reads for each look. Where the Android renderers record a constant, it is used
 * as is. The rest are visual calibrations that keep the looks distinct.
 */
data class LyricsModeProfile(
    val mainSizeSp: Float,
    val maxWidthDp: Int,
    val lineSpacingFactor: Float,
    val minSweepMs: Long,
    val anchorFraction: Float,
    val idleAlpha: Float,
    val manualScrollHoldMs: Long,
    /** Whether a drag keeps the hold open until the finger lifts. The other looks restart the hold on each scroll. */
    val holdsWhileDragged: Boolean,
) {
    companion object {
        /**
         * How long a manual scroll holds off auto-follow for ENHANCED, V2 and SPOTIFY. Each scroll restarts the hold,
         * so it runs from the last one.
         */
        const val MANUAL_SCROLL_HOLD_MS = 3_000L

        /**
         * SimpMusic's hold. A drag suspends follow until the finger lifts, then the hold runs for four seconds,
         * as the Android SimpMusic look does.
         */
        const val SIMPMUSIC_MANUAL_SCROLL_HOLD_MS = 4_000L

        fun of(mode: LyricsMode): LyricsModeProfile =
            when (mode) {
                LyricsMode.ENHANCED -> LyricsModeProfile(
                    mainSizeSp = 26f,
                    maxWidthDp = 860,
                    lineSpacingFactor = 1f,
                    minSweepMs = LyricsSweepGeometry.MIN_SWEEP_MS,
                    anchorFraction = 0.35f,
                    idleAlpha = 0.6f,
                    manualScrollHoldMs = MANUAL_SCROLL_HOLD_MS,
                    holdsWhileDragged = false,
                )
                LyricsMode.SPOTIFY -> LyricsModeProfile(
                    mainSizeSp = 26f,
                    maxWidthDp = 760,
                    lineSpacingFactor = 1f,
                    minSweepMs = LyricsSweepGeometry.MIN_SWEEP_MS,
                    anchorFraction = 0.35f,
                    idleAlpha = 0.6f,
                    manualScrollHoldMs = MANUAL_SCROLL_HOLD_MS,
                    holdsWhileDragged = false,
                )
                LyricsMode.V2 -> LyricsModeProfile(
                    mainSizeSp = 24f,
                    maxWidthDp = 860,
                    lineSpacingFactor = 1f,
                    minSweepMs = 180L,
                    anchorFraction = 0.4f,
                    idleAlpha = 0.55f,
                    manualScrollHoldMs = MANUAL_SCROLL_HOLD_MS,
                    holdsWhileDragged = false,
                )
                LyricsMode.SIMPMUSIC -> LyricsModeProfile(
                    mainSizeSp = 24f,
                    maxWidthDp = 860,
                    lineSpacingFactor = 1.3f,
                    minSweepMs = LyricsSweepGeometry.MIN_SWEEP_MS,
                    anchorFraction = 0.4f,
                    idleAlpha = 0.6f,
                    manualScrollHoldMs = SIMPMUSIC_MANUAL_SCROLL_HOLD_MS,
                    holdsWhileDragged = true,
                )
            }
    }
}
