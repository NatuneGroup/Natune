package moe.rukamori.archivetune.utils

/**
 * Decode-size arithmetic for code that needs an artwork's *colours*, not its detail.
 *
 * Album art routinely arrives at 3000 px on the long edge, and a full `BitmapFactory` decode of
 * it is a multi-megabyte ARGB allocation for a palette pass whose own internal raster is a few
 * hundred pixels wide. `inSampleSize` is the only way to avoid that allocation, and it must be a
 * power of two, so this is shared rather than re-derived at each call site.
 */
internal object ArtworkThumbnailDecode {
    /**
     * Long-edge ceiling for palette input. The palette quantises to a handful of swatches, so
     * anything above this buys no colour accuracy; the sampled decode still lands below twice
     * this size because of the power-of-two rule.
     */
    const val PALETTE_MAX_DIMENSION_PX = 192

    /**
     * The largest power-of-two `inSampleSize` whose decoded long edge is still at least
     * [maxDimensionPx]. Returns 1 for unknown or already-small bounds, so small artwork is
     * never upscaled or rejected.
     */
    fun sampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        maxDimensionPx: Int,
    ): Int {
        require(maxDimensionPx > 0) { "maxDimensionPx must be positive" }
        // BitmapFactory leaves both bounds at -1 when it cannot read a file; a partially invalid
        // pair is not a trustworthy size either, so it takes the same full-decode path.
        if (sourceWidth <= 0 || sourceHeight <= 0) return 1
        val largestDimension = maxOf(sourceWidth, sourceHeight)

        var sampleSize = 1
        while (largestDimension / (sampleSize * 2) >= maxDimensionPx) {
            sampleSize *= 2
        }
        return sampleSize
    }
}
