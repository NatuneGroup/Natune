package moe.rukamori.archivetune.shared.domain.lyrics.romanization

import moe.rukamori.archivetune.shared.domain.lyrics.KaraokeLine
import moe.rukamori.archivetune.shared.domain.lyrics.LyricsWord
import kotlin.test.Test
import kotlin.test.assertEquals

class RomanizationTimedPresentationTest {
    @Test fun rtlCjkLatinAndSplitGraphemesRetainOneLogicalSourceStringAndRanges() {
        val fixtures = listOf(
            listOf("می\u200C", "روم"), listOf("الع", "ربية"), listOf("中", "文"),
            listOf("東", "京"), listOf("Hello ", "world"), listOf("e", "\u0301"),
            listOf("👩\u200D", "🎤"), listOf("\uD840\uDC00", "かな"),
        )
        fixtures.forEach { fragments ->
            val source = fragments.joinToString("")
            val words = fragments.mapIndexed { index, fragment -> LyricsWord(fragment, index + 1.0, index + 2.0) }
            val line = KaraokeLine(time = 1000L, text = source, words = words)
            val result = timedLyricsPresentation(line, LyricsPhonetics())
            assertEquals(source, result.text)
            assertEquals(fragments, result.ranges.map { result.text.substring(checkNotNull(it)) })
            assertEquals(0, checkNotNull(result.ranges.first()).first)
            assertEquals(source.lastIndex, checkNotNull(result.ranges.last()).last)
            assertEquals(words, line.words)
            assertEquals(1000L, line.time)
        }
    }

    @Test fun lineOnlyPhoneticsHaveNoInventedWordRangesAndTogglesKeepSeekIdentity() {
        val words = listOf(LyricsWord("東", 4.5, 5.0), LyricsWord("京", 5.0, 5.5))
        val line = KaraokeLine(time = 4500L, text = "東京", words = words)
        val original = timedLyricsPresentation(line, LyricsPhonetics())
        val lineOnly = timedLyricsPresentation(line, LyricsPhonetics(line = "toukyou"))
        val aligned = timedLyricsPresentation(line, LyricsPhonetics(line = "toukyou", words = listOf("tou", "kyou")))
        assertEquals("東京", original.text)
        assertEquals(listOf(0..0, 1..1), original.ranges)
        assertEquals("toukyou", lineOnly.text)
        assertEquals(listOf<IntRange?>(null, null), lineOnly.ranges)
        assertEquals("tou kyou", aligned.text)
        assertEquals(listOf(0..2, 4..7), aligned.ranges)
        assertEquals(listOf(4.5 to 5.0, 5.0 to 5.5), line.words.map { it.startTime to it.endTime })
        assertEquals(4500L, line.time)
        assertEquals(original, timedLyricsPresentation(line, LyricsPhonetics()))
    }

    @Test fun repeatedWordsMissingAlignmentAndBackgroundDoNotShiftSourceIndices() {
        val line = KaraokeLine(
            time = 1000L,
            text = "la la",
            words = listOf(
                LyricsWord("la ", 1.0, 2.0),
                LyricsWord("aside", 1.5, 2.5, isBackground = true),
                LyricsWord("la", 2.0, 3.0),
            ),
        )
        assertEquals(listOf(0..2, 3..4), timedLyricsPresentation(line, LyricsPhonetics()).ranges)
        assertEquals("la la", timedLyricsPresentation(line, LyricsPhonetics(words = listOf("wrong-count"))).text)
        val absent = KaraokeLine(time = 1000L, text = "la la", words = listOf(LyricsWord("missing", 1.0, 2.0)))
        assertEquals(listOf<IntRange?>(null), timedLyricsPresentation(absent, LyricsPhonetics()).ranges)
    }
}
