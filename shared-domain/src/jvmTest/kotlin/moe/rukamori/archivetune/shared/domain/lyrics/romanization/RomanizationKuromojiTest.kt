package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import kotlin.test.Test
import kotlin.test.assertEquals

/** The real IPADIC tokenizer. Creating it is slow, so every test in this file shares one instance. */
private val sharedKuromoji: KuromojiJapaneseTokenizer by lazy { KuromojiJapaneseTokenizer.create() }

class RomanizationKuromojiTest {
    private val japanese =
        LyricsRomanizationPreferences(romanizeJapanese = true, romanizeKorean = false, romanizeChinese = false, romanizeHindi = false, romanizeOther = false)

    @Test fun tokyoIsOneTokenAtOffsetZero() {
        val tokens = sharedKuromoji.tokenize("東京")
        assertEquals(listOf("東京"), tokens.map { it.surface })
        assertEquals(listOf(0), tokens.map { it.position })
    }

    @Test fun realReadingsAndRomajiForNihongoAndTokyo() {
        mapOf("日本語" to ("ニホンゴ" to "nihongo"), "東京" to ("トウキョウ" to "toukyou")).forEach { (text, expected) ->
            val tokens = sharedKuromoji.tokenize(text)
            assertEquals(text, tokens.joinToString("") { it.surface })
            assertEquals(expected.first, tokens.joinToString("") { it.reading.orEmpty() })
            assertEquals(expected.second, romanizeJapaneseWithTokenizer(text, sharedKuromoji).replace(" ", ""))
        }
    }

    @Test fun aWordCoveringBothSuppliedFragmentsGetsNoInventedSplit() {
        assertEquals(listOf<String?>(null, null), romanizeJapaneseWordsForLine(listOf("東", "京"), "東京", sharedKuromoji))
        assertEquals(listOf("toukyou"), romanizeJapaneseWordsForLine(listOf("東京"), "東京", sharedKuromoji))
    }

    @Test fun symbolWithoutADictionaryReadingIsKeptAsItIs() {
        assertEquals("🦄", romanizeJapaneseWithTokenizer("🦄", sharedKuromoji))
    }

    @Test fun lineAndWordPhoneticsUseTheRealTokenizer() {
        val romanizer = LyricsRomanizer(japaneseTokenizer = sharedKuromoji)
        assertEquals("toukyou", romanizer.romanizeLyricsLine("東京", japanese, sourceLanguage = "ja"))
        val line = KaraokeLine(time = 0L, text = "東京", providerRomanizedLanguage = "ja", words = listOf(LyricsWord("東京", 0.0, 1.0)))
        assertEquals(listOf("toukyou"), romanizer.phoneticsForLine(line, japanese).words)
    }
}
