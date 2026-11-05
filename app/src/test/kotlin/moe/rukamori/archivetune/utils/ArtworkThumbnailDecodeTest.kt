package moe.rukamori.archivetune.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkThumbnailDecodeTest {
    private val paletteBudget = ArtworkThumbnailDecode.PALETTE_MAX_DIMENSION_PX

    @Test
    fun fullResolutionArtworkIsSampledWithinTheBudget() {
        val covers = listOf(3000 to 3000, 1080 to 1920, 4000 to 2250, 2048 to 2048, 4096 to 512)
        for ((width, height) in covers) {
            val sampleSize = ArtworkThumbnailDecode.sampleSize(width, height, paletteBudget)
            val decodedLongEdge = maxOf(width, height) / sampleSize
            assertTrue("$width x $height must actually be sampled down, got $sampleSize", sampleSize > 1)
            assertTrue(
                "$width x $height decoded to $decodedLongEdge px, below the $paletteBudget px budget",
                decodedLongEdge >= paletteBudget,
            )
            assertTrue(
                "$width x $height decoded to $decodedLongEdge px, more than twice the $paletteBudget px budget",
                decodedLongEdge < paletteBudget * 2,
            )
        }
    }

    @Test
    fun artworkAtOrBelowTheBudgetIsNotSampled() {
        assertEquals(1, ArtworkThumbnailDecode.sampleSize(paletteBudget, paletteBudget, paletteBudget))
        assertEquals(1, ArtworkThumbnailDecode.sampleSize(100, 80, paletteBudget))
        assertEquals(1, ArtworkThumbnailDecode.sampleSize(1, 1, paletteBudget))
    }

    @Test
    fun sampleSizeIsAlwaysAPowerOfTwoSoBitmapFactoryAcceptsIt() {
        for (width in listOf(1, 64, 191, 193, 512, 1080, 3000, 8000)) {
            for (height in listOf(1, 100, 1920, 4000)) {
                val sampleSize = ArtworkThumbnailDecode.sampleSize(width, height, paletteBudget)
                assertTrue("sample size $sampleSize is not a power of two", sampleSize > 0)
                assertEquals(0, sampleSize and (sampleSize - 1))
            }
        }
    }

    @Test
    fun unknownBoundsFallBackToAFullDecode() {
        assertEquals(1, ArtworkThumbnailDecode.sampleSize(0, 0, paletteBudget))
        assertEquals(1, ArtworkThumbnailDecode.sampleSize(-1, 3000, paletteBudget))
    }
}
