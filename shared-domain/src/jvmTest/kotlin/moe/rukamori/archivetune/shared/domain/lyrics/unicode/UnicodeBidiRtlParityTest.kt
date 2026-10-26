/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Parity with `Character.getDirectionality`, the call the Android lyric code made. The reference
 * [androidIsRtlText] is the original loop, kept verbatim in shape so the comparison is with the JDK, not with
 * our table.
 */
class UnicodeBidiRtlParityTest {
    private val rtlDirections: Set<Byte> = setOf(
        java.lang.Character.DIRECTIONALITY_RIGHT_TO_LEFT,
        java.lang.Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC,
        java.lang.Character.DIRECTIONALITY_RIGHT_TO_LEFT_EMBEDDING,
        java.lang.Character.DIRECTIONALITY_RIGHT_TO_LEFT_OVERRIDE,
    )
    private val ltrDirections: Set<Byte> = setOf(
        java.lang.Character.DIRECTIONALITY_LEFT_TO_RIGHT,
        java.lang.Character.DIRECTIONALITY_LEFT_TO_RIGHT_EMBEDDING,
        java.lang.Character.DIRECTIONALITY_LEFT_TO_RIGHT_OVERRIDE,
    )

    private fun androidIsRtlText(text: String): Boolean {
        for (codePoint in text.codePoints().toArray()) {
            val direction = java.lang.Character.getDirectionality(codePoint)
            if (direction in rtlDirections) return true
            if (direction in ltrDirections) return false
        }
        return false
    }

    @Test
    fun everyCodePointHasTheSameStrongClassAsTheJdk() {
        var mismatches = 0
        for (codePoint in 0..UNICODE_MAX_CODE_POINT) {
            val direction = java.lang.Character.getDirectionality(codePoint)
            val expectedRtl = direction in rtlDirections
            val expectedLtr = direction in ltrDirections
            if (UnicodeBidi.isStrongRightToLeft(codePoint) != expectedRtl) mismatches++
            if (UnicodeBidi.isStrongLeftToRight(codePoint) != expectedLtr) mismatches++
        }
        assertEquals(0, mismatches, "strong-class mismatches against Character.getDirectionality")
    }

    @Test
    fun randomTextsMatchTheAndroidFirstStrongRule() {
        val pool = intArrayOf(
            0x41, 0x7A, 0xE9, 0x30, 0x35, 0x20, 0x21, 0x22, 0x0300, 0x064B, 0x0661, 0x05D0, 0x05FF, 0x0627,
            0x0645, 0x0915, 0x0E01, 0x4E00, 0x3042, 0x30A2, 0xAC00, 0x1100, 0x1F600, 0x1F3FD, 0x200D, 0x200C,
            0xFE0F, 0x202A, 0x202B, 0x202D, 0x202E, 0x2066, 0x0009, 0xD800, 0xDC00, 0xE000, 0x10FFFF,
        )
        val random = Random(20_261_015)
        repeat(20_000) {
            val builder = StringBuilder()
            repeat(random.nextInt(0, 9)) {
                val codePoint = if (random.nextInt(4) == 0) random.nextInt(0, UNICODE_MAX_CODE_POINT + 1) else pool[random.nextInt(pool.size)]
                builder.appendCodePoint(codePoint)
            }
            val text = builder.toString()
            assertEquals(androidIsRtlText(text), UnicodeBidi.isRtlText(text), "text codepoints ${text.codePoints().toArray().toList()}")
        }
    }
}
