package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the generated Scripts.txt table against the JDK for every code point in 0..U+10FFFF. The JDK
 * is the oracle here, so there are no hand-written expectations: a mismatch means the generator or the
 * lookup is wrong, not the test.
 */
class UnicodeScriptTableParityTest {
    @Test
    fun everyCodePointMatchesTheJdkUnicodeScript() {
        val mismatches = ArrayList<String>()
        var count = 0
        for (codePoint in 0..UNICODE_MAX_CODE_POINT) {
            val expected = java.lang.Character.UnicodeScript.of(codePoint).name
            val actual = UnicodeScripts.scriptOf(codePoint).name
            if (expected != actual) {
                count++
                if (mismatches.size < 20) {
                    mismatches.add("U+${codePoint.toString(16).uppercase()} expected $expected actual $actual")
                }
            }
        }
        assertTrue(count == 0, "$count mismatches, first: $mismatches")
    }

    @Test
    fun enumNamesCoverExactlyTheJdkScripts() {
        val jdk = java.lang.Character.UnicodeScript.values().map { it.name }.toSet()
        val ours = UnicodeScript.entries.map { it.name }.toSet()
        assertEquals(jdk, ours)
    }
}
