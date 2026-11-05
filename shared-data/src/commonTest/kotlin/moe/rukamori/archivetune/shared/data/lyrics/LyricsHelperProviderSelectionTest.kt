package moe.rukamori.archivetune.shared.data.lyrics

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsHelperProviderSelectionTest {
    @Test
    fun aCachedProviderThatIsNowDisabledIsAMissAndTheEnabledProviderIsFetched() =
        runTest {
            val disabled = answering("LrcLib", PLAIN_TEXT)
            val enabled = answering("Kugou", PLAIN_TEXT)
            var enabledNames = setOf("LrcLib", "Kugou")
            val helper = helperOf(listOf(disabled, enabled), backgroundScope, isEnabled = { it in enabledNames })

            assertEquals("LrcLib", helper.getLyricsWithProvider(request()).providerName)
            assertEquals(1, disabled.calls)

            enabledNames = setOf("Kugou")
            val result = helper.getLyricsWithProvider(request())

            assertEquals("Kugou", result.providerName)
            assertEquals(PLAIN_TEXT, result.lyrics)
            assertEquals(1, disabled.calls)
            assertEquals(2, enabled.calls)
        }

    @Test
    fun aCachedProviderThatIsNowUnavailableIsAMissAndTheAvailableProviderIsFetched() =
        runTest {
            val unavailable = answering("LrcLib", PLAIN_TEXT)
            val available = answering("Kugou", PLAIN_TEXT)
            var availableNames = setOf("LrcLib", "Kugou")
            val helper = helperOf(listOf(unavailable, available), backgroundScope, isAvailable = { it in availableNames })

            assertEquals("LrcLib", helper.getLyricsWithProvider(request()).providerName)

            availableNames = setOf("Kugou")
            val result = helper.getLyricsWithProvider(request())

            assertEquals("Kugou", result.providerName)
            assertEquals(1, unavailable.calls)
            assertEquals(2, available.calls)
        }

    @Test
    fun aCachedProviderThatIsStillEnabledIsServedWithoutASecondFetch() =
        runTest {
            val provider = answering("LrcLib", PLAIN_TEXT)
            val helper = helperOf(listOf(provider), backgroundScope)

            helper.getLyrics(request())
            helper.getLyrics(request())

            assertEquals(1, provider.calls)
        }

    @Test
    fun aCachedAllResultsEntryFromADisabledProviderIsSkippedForTheEnabledOne() =
        runTest {
            val disabled = answering("LrcLib", PLAIN_TEXT)
            val enabled = answering("Kugou", PLAIN_TEXT)
            var enabledNames = setOf("LrcLib", "Kugou")
            val helper = helperOf(listOf(disabled, enabled), backgroundScope, isEnabled = { it in enabledNames })

            val collected = mutableListOf<String>()
            helper.getAllLyrics(request()) { collected += it.providerName }
            assertEquals(listOf("LrcLib", "Kugou"), collected)

            enabledNames = setOf("Kugou")
            val result = helper.getLyricsWithProvider(request())

            assertEquals("Kugou", result.providerName)
            assertEquals(1, disabled.calls)
            assertEquals(1, enabled.calls)
        }
}
