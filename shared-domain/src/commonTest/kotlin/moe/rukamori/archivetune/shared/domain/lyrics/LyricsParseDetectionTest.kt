package moe.rukamori.archivetune.shared.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plain-text passthrough, display normalisation and the detection helpers the UI routes on. */
class LyricsParseDetectionTest {
    @Test
    fun normalizeStripsOnlyTransportWrappers() {
        assertEquals("plain words\nline two", LyricsNormalizer.normalize("  plain words\nline two  "))
        assertEquals("hello", LyricsNormalizer.normalize("\uFEFFhello"))
        assertEquals("[00:01.00]Hi", LyricsNormalizer.normalize("```\n[00:01.00]Hi\n```"))
    }

    @Test
    fun plainTextWithoutTimestampsHasNoTimedRows() {
        assertTrue(LyricsParser.parse("just words\nand more words").isEmpty())
    }

    @Test
    fun plainTextDisplayIsCollapsedAndTrimmed() {
        assertEquals("Hello there\nworld", LyricsDetection.displayText("  Hello   there \n\n  world  "))
    }

    @Test
    fun enhancedLrcDisplayTextDropsStampsAndKeepsOneSpace() {
        assertEquals(
            "Hello world",
            LyricsDetection.displayText("[00:12.000]<00:12.000>Hello <00:12.500>world"),
        )
    }

    @Test
    fun sentinelAndBlankInputHaveNoDisplayText() {
        assertEquals("", LyricsDetection.displayText(LyricsSentinels.NOT_FOUND))
        assertEquals("", LyricsDetection.displayText("   "))
        assertFalse(LyricsDetection.hasMeaningfulContent("[00:01.00]"))
        assertEquals(LyricsSentinels.NOT_FOUND, LyricsDetection.lyricsOrNotFound("   "))
        assertEquals("[00:01.00]Hi", LyricsDetection.lyricsOrNotFound("[00:01.00]Hi"))
    }

    @Test
    fun trueWordSyncRejectsLineOnlyAndIdenticalStarts() {
        assertFalse(LyricsDetection.hasTrueWordSync(SyncedLine(time = 1_000L, text = "Hi")))

        val identicalStarts =
            KaraokeLine(
                time = 1_000L,
                text = "ab",
                words = listOf(LyricsWord("a", 1.0, 1.5), LyricsWord("b", 1.0, 1.5)),
            )
        assertFalse(LyricsDetection.hasTrueWordSync(identicalStarts))
    }

    @Test
    fun trueWordSyncAcceptsRealPerWordTiming() {
        val timed =
            KaraokeLine(
                time = 1_000L,
                text = "a b c",
                words =
                    listOf(
                        LyricsWord("a ", 1.0, 1.5),
                        LyricsWord("b ", 1.4, 2.0),
                        LyricsWord("c", 2.5, 3.0),
                    ),
            )

        assertTrue(LyricsDetection.hasTrueWordSync(timed))
    }

    @Test
    fun upgradeOnlyTargetsRemoteLineOnlyText() {
        val lrc = "[00:01.00]Hello"
        val wordSynced = "[00:12.000]<00:12.000>Hello <00:12.500>world"

        assertTrue(LyricsDetection.needsWordSyncedUpgrade(true, isRemoteSource = true, storedLyrics = lrc))
        assertFalse(LyricsDetection.needsWordSyncedUpgrade(false, isRemoteSource = true, storedLyrics = lrc))
        assertFalse(LyricsDetection.needsWordSyncedUpgrade(true, isRemoteSource = false, storedLyrics = lrc))
        assertFalse(LyricsDetection.needsWordSyncedUpgrade(true, isRemoteSource = true, storedLyrics = wordSynced))
        assertFalse(
            LyricsDetection.needsWordSyncedUpgrade(
                true,
                isRemoteSource = true,
                storedLyrics = LyricsSentinels.NOT_FOUND,
            ),
        )
        assertFalse(LyricsDetection.needsWordSyncedUpgrade(true, isRemoteSource = true, storedLyrics = "   "))
        assertFalse(LyricsDetection.needsWordSyncedUpgrade(true, isRemoteSource = true, storedLyrics = null))
    }

    @Test
    fun translationDetectionRequiresARealTranslation() {
        assertTrue(LyricsDetection.hasTranslation("[00:16.50] Hello there\n[00:16.50] Hola"))
        assertFalse(LyricsDetection.hasTranslation("[00:16.50] Hello there\n[00:17.50] Hola"))
        assertFalse(LyricsDetection.hasTranslation("   "))
    }

    @Test
    fun formatDetectionRoutesEachDocumentKind() {
        val qrc = "[1000,2000]Hello(1000,800) world(1800,1200)"
        val lrc = "[00:01.00]Hi"

        assertTrue(LyricsDetection.isLineSyncedLrc(qrc))
        assertTrue(LyricsDetection.isLineSyncedLrc(lrc))
        assertFalse(LyricsDetection.isLineSyncedLrc("plain"))
        assertFalse(LyricsDetection.isTtml(lrc))
    }
}
