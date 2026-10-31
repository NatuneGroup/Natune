package moe.rukamori.archivetune.shared.domain.dsp.silence

/** Long silence is skipped only in tracks longer than this, so a short pause is never jumped. */
private const val INSTANT_SKIP_MIN_DURATION_MS = 15_000L

/** How far one skip moves the position. */
private const val INSTANT_SKIP_STEP_MS = 15_000L

/** A skip never lands closer than this to the end of the track. */
private const val INSTANT_SKIP_END_MARGIN_MS = 500L

/**
 * Tells a silent stretch from per-frame peaks of normalized float PCM. A frame whose peak is below the instant threshold
 * counts as silent, and a louder frame ends the run. The run is silent once it reaches two seconds of frames.
 */
class InstantSilenceDetector(
    private val sampleRate: Int,
) {
    private var silentFrames = 0L

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    /** Records one frame by its peak across channels, as a fraction of full scale. */
    fun frame(peak: Float) {
        if (peak < INSTANT_SILENT_PEAK) {
            silentFrames++
        } else {
            silentFrames = 0L
        }
    }

    /** True once the silent run reaches two seconds of frames. */
    val isSilent: Boolean
        get() = silentFrames >= sampleRate.toLong() * 2L

    /** Forgets the run, at a seek or any other discontinuity. */
    fun reset() {
        silentFrames = 0L
    }

    companion object {
        /** 256 in 16-bit PCM units, as a fraction of full scale. */
        const val INSTANT_SILENT_PEAK = 256f / 32768f
    }
}

/**
 * The position to seek to for skipping a silent stretch, or null when no seek is worth making. Tracks of 15 s or less are
 * never skipped. Otherwise the target is 15 s past [positionMs], kept at least 500 ms before the end, and it is returned
 * only when it is later than the position.
 */
fun instantSilenceSeekTarget(positionMs: Long, durationMs: Long): Long? {
    if (durationMs <= INSTANT_SKIP_MIN_DURATION_MS) return null
    val target = minOf(positionMs + INSTANT_SKIP_STEP_MS, durationMs - INSTANT_SKIP_END_MARGIN_MS)
    return target.takeIf { it > positionMs }
}
