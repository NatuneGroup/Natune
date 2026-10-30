package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.ui.menu.playerqueue.PlayerSourceQuality

class DesktopSourceSearchTest {
    private fun song(id: String = "track") = ContentItem(ContentRef("provider", ContentKind.SONG, id), "Song",
        subtitle = "Provider artist", durationMs = 180_000, album = "Album")

    @Test fun supportedBackendsRunInParallelButRowsKeepAndroidSearchOrder() = runTest {
        val backendOrder = DesktopSourceSearch.SEARCH_ORDER.filter { it != AudioSourceType.YOUTUBE }
        val backends: Map<AudioSourceType, suspend (String) -> List<ContentItem>> =
            backendOrder.associateWith { source ->
                { _: String ->
                    delay((backendOrder.size - backendOrder.indexOf(source)).toLong() * 100)
                    listOf(song())
                }
            }
        val search = DesktopSourceSearch(backends)
        val rows = search.search(" Song ", null, includeAmazon = true) { query ->
            assertEquals("Song", query)
            listOf(song())
        }
        assertEquals(DesktopSourceSearch.SEARCH_ORDER, rows.map { it.source })
        assertEquals("Provider artist", rows.first().artist)
        assertTrue(rows.first().isYouTubeSong)
        assertTrue(rows.drop(1).none { it.isYouTubeSong })
        assertTrue(rows.all { it.quality == PlayerSourceQuality.NONE })
        assertTrue(testScheduler.currentTime < 1000)
    }

    @Test fun filtersAndAmazonGateNeverCallOtherServices() = runTest {
        val seen = mutableListOf<AudioSourceType>()
        val backends: Map<AudioSourceType, suspend (String) -> List<ContentItem>> =
            listOf(AudioSourceType.QOBUZ, AudioSourceType.AMAZON).associateWith { source ->
                { seen += source; listOf(song()) }
            }
        val search = DesktopSourceSearch(backends)
        val noYoutube: suspend (String) -> List<ContentItem> = { error("filtered out") }
        assertTrue(search.search("Song", AudioSourceType.SPOTIFY, true, noYoutube).isEmpty())
        assertTrue(search.search("Song", AudioSourceType.AMAZON, false, noYoutube).isEmpty())
        assertTrue(search.search("x", AudioSourceType.QOBUZ, false, noYoutube).isEmpty())
        assertTrue(seen.isEmpty())
        assertEquals(AudioSourceType.QOBUZ,
            search.search("Song", AudioSourceType.QOBUZ, false, noYoutube).single().source)
        assertEquals(listOf(AudioSourceType.QOBUZ), seen)
        assertFalse(AudioSourceType.APPLE in search.searchableSources)
        assertFalse(AudioSourceType.QQ in search.searchableSources)
    }

    @Test fun providerFailureAndOwnTimeoutDoNotHideOtherRowsButCancellationEscapes() = runTest {
        val search = DesktopSourceSearch(mapOf(
            AudioSourceType.QOBUZ to { _: String -> error("provider failed") },
            AudioSourceType.TIDAL to { _: String -> delay(DesktopSourceSearch.SEARCH_TIMEOUT_MS + 1); listOf(song()) },
        ))
        val rows = search.search("Song", null, false) { listOf(song()) }
        assertEquals(listOf(AudioSourceType.YOUTUBE), rows.map { it.source })
        val cancelled = DesktopSourceSearch(mapOf(AudioSourceType.QOBUZ to { _: String ->
            throw CancellationException("caller cancelled")
        }))
        assertFailsWith<CancellationException> { cancelled.search("Song", AudioSourceType.QOBUZ, false) { emptyList() } }
    }

    @Test fun rowsAreDeduplicatedAndBoundedWithoutInventingArtistIdsOrQuality() = runTest {
        val rows = (0..20).flatMap { listOf(song("$it"), song("$it")) } +
            ContentItem(ContentRef("provider", ContentKind.ALBUM, "album"), "Not a song")
        val search = DesktopSourceSearch(mapOf(AudioSourceType.DEEZER to { _: String -> rows }))
        val result = search.search("Song", AudioSourceType.DEEZER, false) { error("filtered") }
        assertEquals(DesktopSourceSearch.RESULTS_PER_SOURCE, result.size)
        assertEquals(result.size, result.map { it.trackId }.distinct().size)
        assertEquals("Album", result.first().album)
    }
}
