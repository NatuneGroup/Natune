package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The bundled Mandarin table: per-character readings, polyphonic phrase overrides and pass-through. */
class RomanizationMandarinTest {
    @Test
    fun monosyllabicLineUsesFirstDictionaryReading() {
        assertEquals("wǒ ài nǐ", MandarinRomanization.romanize("我爱你"))
        assertEquals("zhōng guó", MandarinRomanization.romanize("中国"))
        assertEquals("shì jiè", MandarinRomanization.romanize("世界"))
    }

    @Test
    fun phraseOverrideCorrectsPolyphonicReading() {
        // 乐's first dictionary reading is lè; the phrase entry re-reads it yuè.
        assertEquals("lè", MandarinRomanization.romanize("乐"))
        assertEquals("yīn yuè", MandarinRomanization.romanize("音乐"))
        // 重's first reading is zhòng; in 重庆 it is chóng.
        assertEquals("chóng qìng", MandarinRomanization.romanize("重庆"))
    }

    @Test
    fun nonHanTextPassesThroughAndReportsNoCoverage() {
        assertNull(MandarinRomanization.romanize("hello world"))
        assertTrue(!MandarinRomanization.covers("hello world"))
        assertTrue(MandarinRomanization.covers("中"))
    }

    @Test
    fun mixedLineKeepsNonHanTextWithScriptBoundaries() {
        assertEquals("nǐ hǎo world", MandarinRomanization.romanize("你好world"))
        assertEquals("A wǒ ài nǐ", MandarinRomanization.romanize("A我爱你"))
    }

    @Test
    fun longestPhraseMatchWinsOverPerCharacter() {
        // 一丁不识 is a four-character phrase entry; it must win over per-character naive joins.
        assertEquals("yī dīng bù shí", MandarinRomanization.romanize("一丁不识"))
    }
}
