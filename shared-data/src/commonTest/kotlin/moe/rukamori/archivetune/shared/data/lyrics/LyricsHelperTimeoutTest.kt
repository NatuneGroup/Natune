package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsHelperTimeoutTest {
    @Test
    fun aHungProviderIsAbandonedAtEightSecondsAndTheOthersAreKept() =
        runTest {
            val helper =
                helperOf(
                    listOf(neverAnswering("LrcLib"), answering("Kugou", PLAIN_TEXT)),
                    backgroundScope,
                )

            val result = helper.getLyricsWithProvider(request())

            assertEquals("Kugou", result.providerName)
            assertEquals(8_000L, testScheduler.currentTime)
        }

    @Test
    fun aHungWordSyncedProviderIsAbandonedAtFifteenSecondsAndTheOthersAreKept() =
        runTest {
            val helper =
                helperOf(
                    listOf(neverAnswering("BetterLyrics"), answering("YouLyPlus", WORD_SYNCED_TTML)),
                    backgroundScope,
                    prioritizeWordSynced = true,
                )

            val result = helper.getLyricsWithProvider(request())

            assertEquals("YouLyPlus", result.providerName)
            assertEquals(15_000L, testScheduler.currentTime)
        }

    @Test
    fun getAllLyricsStreamsTheFastResultsAndDropsTheHungProviderAtEightSeconds() =
        runTest {
            val streamed = mutableListOf<LyricsResult>()
            val helper =
                helperOf(
                    listOf(neverAnswering("LrcLib"), answering("Kugou", PLAIN_TEXT)),
                    backgroundScope,
                )

            helper.getAllLyrics(request()) { streamed += it }

            assertEquals(listOf("Kugou"), streamed.map { it.providerName })
            assertEquals(8_000L, testScheduler.currentTime)
        }
}
