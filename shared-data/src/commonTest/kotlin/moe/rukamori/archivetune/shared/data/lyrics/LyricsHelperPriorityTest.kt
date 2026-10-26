/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsHelperPriorityTest {
    @Test
    fun wordSyncedBeatsLineSyncedAndPlainWhateverTheProviderOrder() =
        runTest {
            val plain = answering("LrcLib", PLAIN_TEXT)
            val lineSynced = answering("Kugou", LINE_SYNCED_LRC)
            val wordSynced = answering("Unison", WORD_SYNCED_TTML)
            val helper = helperOf(listOf(plain, lineSynced, wordSynced), backgroundScope)

            val result = helper.getLyricsWithProvider(request())

            assertEquals("Unison", result.providerName)
        }

    @Test
    fun lineSyncedBeatsPlainEvenWhenThePlainProviderIsFirst() =
        runTest {
            val helper =
                helperOf(
                    listOf(answering("LrcLib", PLAIN_TEXT), answering("Kugou", LINE_SYNCED_LRC)),
                    backgroundScope,
                )

            assertEquals("Kugou", helper.getLyricsWithProvider(request()).providerName)
        }

    @Test
    fun equalTiersKeepProviderOrderEvenWhenALaterProviderAnswersFirst() =
        runTest {
            val earlierButSlower = answeringAfter("LrcLib", 500, PLAIN_TEXT)
            val laterButFaster = answering("Kugou", PLAIN_TEXT)
            val helper = helperOf(listOf(earlierButSlower, laterButFaster), backgroundScope)

            assertEquals("LrcLib", helper.getLyricsWithProvider(request()).providerName)
        }

    @Test
    fun aSlowWordSyncedAnswerBeatsAFastPlainOneInsideTheWindow() =
        runTest {
            val fastPlain = answering("LrcLib", PLAIN_TEXT)
            val slowWordSynced = answeringAfter("Unison", 7_000, WORD_SYNCED_TTML)
            val helper = helperOf(listOf(fastPlain, slowWordSynced), backgroundScope)

            val result = helper.getLyricsWithProvider(request())

            assertEquals("Unison", result.providerName)
            assertEquals(7_000L, testScheduler.currentTime)
        }

    @Test
    fun prioritizedSweepPicksTheCanonicalWordSyncedProviderNotTheFirstToArrive() =
        runTest {
            val helper =
                helperOf(
                    listOf(
                        answering("LrcLib", PLAIN_TEXT),
                        answeringAfter("BetterLyrics", 3_000, WORD_SYNCED_TTML),
                        answering("YouLyPlus", WORD_SYNCED_TTML),
                        answeringAfter("Unison", 1_000, WORD_SYNCED_TTML),
                    ),
                    backgroundScope,
                    prioritizeWordSynced = true,
                )

            assertEquals("BetterLyrics", helper.getLyricsWithProvider(request()).providerName)
        }

    @Test
    fun prioritizedSweepAcceptsAWordSyncedAnswerThatArrivesAfterEightSeconds() =
        runTest {
            val helper =
                helperOf(
                    listOf(
                        answeringAfter("BetterLyrics", 12_000, WORD_SYNCED_TTML),
                        answering("LrcLib", PLAIN_TEXT),
                    ),
                    backgroundScope,
                    prioritizeWordSynced = true,
                )

            val result = helper.getLyricsWithProvider(request())

            assertEquals("BetterLyrics", result.providerName)
            assertEquals(12_000L, testScheduler.currentTime)
        }

    @Test
    fun prioritizedSweepFallsBackToTheNormalFlowWhenNothingIsWordSynced() =
        runTest {
            val helper =
                helperOf(
                    listOf(answering("BetterLyrics", PLAIN_TEXT), answering("LrcLib", PLAIN_TEXT)),
                    backgroundScope,
                    prioritizeWordSynced = true,
                )

            assertEquals("BetterLyrics", helper.getLyricsWithProvider(request()).providerName)
        }

    @Test
    fun prioritizedSweepSkipsDisabledWordCapableProviders() =
        runTest {
            // Deliberate deviation: Android sweeps all three regardless of the per-provider switch.
            val disabled = answering("BetterLyrics", WORD_SYNCED_TTML)
            val helper =
                helperOf(
                    listOf(disabled, answering("YouLyPlus", WORD_SYNCED_TTML)),
                    backgroundScope,
                    prioritizeWordSynced = true,
                    isEnabled = { it != "BetterLyrics" },
                )

            assertEquals("YouLyPlus", helper.getLyricsWithProvider(request()).providerName)
            assertEquals(0, disabled.calls)
        }

    @Test
    fun preferredProviderOnlyUsesTheFirstEnabledProviderInListOrder() =
        runTest {
            val disabled = answering("LrcLib", PLAIN_TEXT)
            val first = answering("Kugou", PLAIN_TEXT)
            val helper =
                helperOf(
                    listOf(disabled, first, answering("SimpMusic", PLAIN_TEXT)),
                    backgroundScope,
                    isEnabled = { it != "LrcLib" },
                )

            val result = helper.getLyricsWithProvider(request(), preferredProviderOnly = true)

            assertEquals("Kugou", result.providerName)
            assertEquals(0, disabled.calls)
        }

    @Test
    fun aSentinelAnswerIsNotRankedAsLyrics() =
        runTest {
            val helper =
                helperOf(
                    listOf(answering("LrcLib", "   \n  "), answering("Kugou", PLAIN_TEXT)),
                    backgroundScope,
                )

            val result = helper.getLyricsWithProvider(request())

            assertEquals("Kugou", result.providerName)
            assertEquals(PLAIN_TEXT, result.lyrics)
        }
}
