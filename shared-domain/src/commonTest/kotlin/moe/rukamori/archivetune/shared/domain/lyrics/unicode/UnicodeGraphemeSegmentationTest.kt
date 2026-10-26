package moe.rukamori.archivetune.shared.domain.lyrics.unicode

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Expectations follow UAX #29 for Unicode 15.0.0. GB9c (Indic conjuncts) arrived in 15.1, so a Devanagari
 * virama followed by a consonant is two clusters here, which is what the JDK's break iterator also does.
 */
class UnicodeGraphemeSegmentationTest {
    private fun segments(text: String): List<String> = GraphemeClusterSegmenter.segment(text)

    private val manWomanGirl = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // 👨‍👩‍👧
    private val singer = "\uD83D\uDC69\u200D\uD83C\uDFA4" // 👩‍🎤
    private val usFlag = "\uD83C\uDDFA\uD83C\uDDF8" // 🇺🇸
    private val frFlag = "\uD83C\uDDEB\uD83C\uDDF7" // 🇫🇷
    private val regionalU = "\uD83C\uDDFA" // 🇺 alone
    private val regionalF = "\uD83C\uDDEB"
    private val regionalR = "\uD83C\uDDF7"
    private val regionalD = "\uD83C\uDDE9"

    @Test
    fun emptyTextHasNoClusters() {
        assertEquals(emptyList(), segments(""))
        assertEquals(0, GraphemeClusterSegmenter.graphemeCount(""))
    }

    @Test
    fun crLfStaysTogetherAndLoneBreaksStandAlone() {
        assertEquals(listOf("\r\n"), segments("\r\n"))
        assertEquals(listOf("\r\n", "\r\n"), segments("\r\n\r\n"))
        assertEquals(listOf("a", "\r\n", "b"), segments("a\r\nb"))
        assertEquals(listOf("\n", "\r"), segments("\n\r"))
    }

    @Test
    fun controlsBreakOnBothSidesOfTheCombiningMark() {
        assertEquals(listOf("\u0001", "\u0301"), segments("\u0001\u0301"))
        assertEquals(listOf("\n", "\u0301"), segments("\n\u0301"))
    }

    @Test
    fun combiningMarksAttachToTheirBase() {
        assertEquals(listOf("e\u0301"), segments("e\u0301"))
        assertEquals(listOf("e\u0301\u0301", "x"), segments("e\u0301\u0301x"))
        assertEquals(listOf("\u304B\u3099", "\u306A"), segments("\u304B\u3099\u306A")) // が, な
    }

    @Test
    fun hangulJamoSequencesComposeSyllables() {
        assertEquals(listOf("\u1100\u1161\u11A8"), segments("\u1100\u1161\u11A8")) // 각
        assertEquals(listOf("\u1100\u1100"), segments("\u1100\u1100")) // L L (GB6)
        assertEquals(listOf("\u1100\uAC00"), segments("\u1100\uAC00")) // L LV (GB6)
        assertEquals(listOf("\uAC00\u11A8"), segments("\uAC00\u11A8")) // LV T (GB7/GB8)
        assertEquals(listOf("\uAC01\u11A8"), segments("\uAC01\u11A8")) // LVT T (GB8)
        assertEquals(listOf("\u1161\u1161"), segments("\u1161\u1161")) // V V (GB7)
        assertEquals(listOf("\u11A8\u11A8"), segments("\u11A8\u11A8")) // T T (GB8)
    }

    @Test
    fun hangulSyllablesWithoutJoiningRulesStaySeparate() {
        assertEquals(listOf("\uAC00", "\uAC00"), segments("\uAC00\uAC00"))
        assertEquals(listOf("\u11A8", "\u1100"), segments("\u11A8\u1100")) // T then L: no rule joins them
        assertEquals(listOf("\u1100\u1161", "\u1100"), segments("\u1100\u1161\u1100")) // V then L breaks (GB999)
    }

    @Test
    fun zwjFamilyEmojiIsOneCluster() {
        assertEquals(listOf(manWomanGirl), segments(manWomanGirl))
        assertEquals(listOf(singer), segments(singer))
        assertEquals(listOf("\uD83D\uDE00\u200D\uD83D\uDE00"), segments("\uD83D\uDE00\u200D\uD83D\uDE00"))
    }

    @Test
    fun zwjWithoutAPictographBeforeItDoesNotJoinTheNextCharacter() {
        assertEquals(listOf("a\u200D", "b"), segments("a\u200Db"))
        assertEquals(listOf("\u200D", "\uD83D\uDC69"), segments("\u200D\uD83D\uDC69"))
        assertEquals(listOf("\uD83D\uDC68\u200D", "b"), segments("\uD83D\uDC68\u200Db"))
    }

    @Test
    fun flagPairsFormOneClusterEach() {
        assertEquals(listOf(usFlag), segments(usFlag))
        assertEquals(listOf(usFlag, frFlag), segments(usFlag + frFlag))
        assertEquals(listOf("a", usFlag), segments("a$usFlag"))
    }

    @Test
    fun oddRegionalIndicatorCountLeavesALoneTrailingIndicator() {
        assertEquals(listOf(regionalU), segments(regionalU))
        assertEquals(listOf(usFlag, regionalF), segments(usFlag + regionalF))
        assertEquals(listOf(usFlag, frFlag, regionalD), segments(usFlag + frFlag + regionalD))
        // Pairs form from the start of the run, so three indicators are (U F) then R.
        assertEquals(listOf(regionalU + regionalF, regionalR), segments(regionalU + regionalF + regionalR))
    }

    @Test
    fun skinToneModifiersExtendTheirBaseEmoji() {
        assertEquals(listOf("\uD83D\uDC4D\uD83C\uDFFD"), segments("\uD83D\uDC4D\uD83C\uDFFD")) // 👍🏽
        assertEquals(
            listOf("\uD83D\uDC4B\uD83C\uDFFB", "\uD83D\uDC4B\uD83C\uDFFF"), // 👋🏻 then 👋🏿
            segments("\uD83D\uDC4B\uD83C\uDFFB\uD83D\uDC4B\uD83C\uDFFF"),
        )
    }

    @Test
    fun devanagariSpacingMarkAttachesToItsConsonant() {
        assertEquals(listOf("\u0915\u093F"), segments("\u0915\u093F")) // कि
        assertEquals(listOf("\u0915\u093F", "\u0915\u093F"), segments("\u0915\u093F\u0915\u093F"))
    }

    @Test
    fun devanagariViramaJoinsOnlyTheMarkNotTheFollowingConsonantInUnicode15() {
        // Virama is Extend, so it joins the consonant before it. The following consonant is a new cluster
        // because GB9c (conjuncts) does not exist until Unicode 15.1.
        assertEquals(listOf("\u0915\u094D", "\u0937"), segments("\u0915\u094D\u0937")) // क्ष
    }

    @Test
    fun thaiSaraAmIsASpacingMarkThatStaysWithItsConsonant() {
        assertEquals(listOf("\u0E01\u0E33"), segments("\u0E01\u0E33")) // กำ
    }

    @Test
    fun prependCharacterAttachesToTheFollowingCharacter() {
        assertEquals(listOf("\u0600" + "1"), segments("\u0600" + "1"))
    }

    @Test
    fun variationSelectorsAttachToTheirBase() {
        assertEquals(listOf("\u2764\uFE0F"), segments("\u2764\uFE0F")) // ❤️
        assertEquals(listOf("\u2764\uFE0E"), segments("\u2764\uFE0E")) // text-style heart
        assertEquals(listOf("1\uFE0F\u20E3"), segments("1\uFE0F\u20E3")) // keycap 1
        assertEquals(listOf("a\uFE0F", "b"), segments("a\uFE0Fb"))
        assertEquals(listOf("\uFE0F"), segments("\uFE0F"))
    }

    @Test
    fun loneSurrogateIsItsOwnClusterUnlessAnExtendOrSpacingMarkFollows() {
        assertEquals(listOf("\uD800", "a"), segments("\uD800a"))
        // UAX #29 treats an unpaired surrogate as GCB Other, so GB9 and GB9a keep a following mark with it.
        // The JDK BreakIterator splits these; that divergence is recorded in UnicodeGraphemeBreakParityTest.
        assertEquals(listOf("\uD800\uD83C\uDFFD"), segments("\uD800\uD83C\uDFFD"))
        assertEquals(listOf("\uD800\u093F"), segments("\uD800\u093F"))
        assertEquals(listOf("\uDC00\u200C"), segments("\uDC00\u200C"))
    }

    @Test
    fun joinedSegmentsReassembleTheOriginalText() {
        val text = "Hi \uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67\r\n$usFlag\u1100\u1161\u11A8\u0915\u093F"
        assertEquals(text, segments(text).joinToString(""))
        assertEquals(segments(text).size, GraphemeClusterSegmenter.graphemeCount(text))
    }
}
