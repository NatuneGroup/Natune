package moe.rukamori.archivetune.shared.domain.dsp

/**
 * Triangular dither for the float-to-PCM reduction. The noise is one least-significant bit peak to peak at
 * [bitDepth], the amount that decorrelates quantization error without audibly raising the floor.
 *
 * [seed] makes the sequence reproducible, so a native port can be checked sample for sample against the
 * Kotlin reference. The seed is only read when a graph builds its dither stage, never per block.
 */
data class DspDither(
    val enabled: Boolean = false,
    val bitDepth: Int = DEFAULT_BIT_DEPTH,
    val seed: Int = DEFAULT_SEED,
) {
    init {
        require(bitDepth in MIN_BIT_DEPTH..MAX_BIT_DEPTH) { "bitDepth must be within $MIN_BIT_DEPTH..$MAX_BIT_DEPTH" }
    }

    companion object {
        const val MIN_BIT_DEPTH = 8
        const val MAX_BIT_DEPTH = 32
        const val DEFAULT_BIT_DEPTH = 16

        /** 0x9E3779B9 as a signed 32-bit value, the golden-ratio constant the native dither starts from. */
        const val DEFAULT_SEED = -1640531527

        val OFF: DspDither = DspDither()
    }
}
