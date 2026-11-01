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
    /**
     * ENHANCED's refinements: the feathered sweep, glow only for held syllables, interlude dots, a staggered depth
     * change, and rows left unblurred while the list is held. The other looks keep their own motion.
     */
    val refinedMotion: Boolean = false,
    /** The quick scale the sung line pops to, or 1 for a look that keeps the line's size. */
    val activeScale: Float = 1f,
) {
    companion object {
        /**
         * How long a manual scroll holds off auto-follow for ENHANCED, V2 and SPOTIFY. Each scroll restarts the hold,
         * so it runs from the last one.
         */
        const val MANUAL_SCROLL_HOLD_MS = 4_500L

        /**
         * SimpMusic's hold. A drag suspends follow until the finger lifts, then the hold runs for four seconds,
         * as the Android SimpMusic look does.
         */
        const val SIMPMUSIC_MANUAL_SCROLL_HOLD_MS = 4_000L

        /** The main-line size the saved lyrics text-size setting stores. The looks are tuned around it. */
        const val TEXT_SIZE_DEFAULT_SP = 26f

        /** The bounds of that setting. A stored value outside them is clamped, so a bad value cannot break the layout. */
        const val TEXT_SIZE_MIN_SP = 16f
        const val TEXT_SIZE_MAX_SP = 36f

        /**
         * The profile of [mode] with its main line at the saved [textSizeSp]. Every look moves by the same number of
         * sp from its own tuned size, so the default keeps each look as it was, and ENHANCED follows the setting exactly.
         */
        fun of(mode: LyricsMode, textSizeSp: Float = TEXT_SIZE_DEFAULT_SP): LyricsModeProfile {
            val base = baseOf(mode)
            val requested = if (textSizeSp.isNaN()) TEXT_SIZE_DEFAULT_SP else textSizeSp.coerceIn(TEXT_SIZE_MIN_SP, TEXT_SIZE_MAX_SP)
            val shift = requested - TEXT_SIZE_DEFAULT_SP
            return if (shift == 0f) base else base.copy(mainSizeSp = base.mainSizeSp + shift)
        }

        private fun baseOf(mode: LyricsMode): LyricsModeProfile =
            when (mode) {
                LyricsMode.ENHANCED -> LyricsModeProfile(
                    mainSizeSp = 26f,
                    maxWidthDp = 860,
                    lineSpacingFactor = 1f,
                    minSweepMs = LyricsSweepGeometry.MIN_SWEEP_MS,
                    anchorFraction = 0.35f,
                    idleAlpha = 0.4f,
                    manualScrollHoldMs = MANUAL_SCROLL_HOLD_MS,
                    holdsWhileDragged = false,
                    refinedMotion = true,
                    activeScale = 1.05f,
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
