/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */

package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsHelperMediaIdPolicyTest {
    private val youTubeId = "dQw4w9WgXcQ"
    private val telegramId = "telegram://track/-1001234567890/42/7/uniqueId"
    private val lrcLib = LrcLibLyricsProvider(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))
    private val simpMusic = SimpMusicLyricsProvider(HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }))

    @Test
    fun youTubeIdsAreSupportedByEveryProvider() {
        assertTrue(LyricsMediaIdPolicy.supportsMediaId(simpMusic, youTubeId))
        assertTrue(LyricsMediaIdPolicy.supportsMediaId(lrcLib, youTubeId))
    }

    @Test
    fun telegramIdsSkipTheYouTubeKeyedSimpMusicProviderOnly() {
        assertTrue(LyricsMediaIdPolicy.isTelegramMediaId(telegramId))
        assertFalse(LyricsMediaIdPolicy.supportsMediaId(simpMusic, telegramId))
        assertTrue(LyricsMediaIdPolicy.supportsMediaId(lrcLib, telegramId))
    }

    @Test
    fun localMediaIdsAreNonYouTubeIdsWhateverTheSchemeCase() {
        listOf(
            "content://media/external/audio/media/42",
            "file:///storage/emulated/0/Music/song.mp3",
            "CONTENT://media/external/audio/media/42",
            "android.resource://moe.rukamori.archivetune/raw/clip",
        ).forEach { id ->
            assertTrue(LyricsMediaIdPolicy.isLocalMediaId(id), id)
            assertFalse(LyricsMediaIdPolicy.supportsMediaId(simpMusic, id), id)
        }
    }

    @Test
    fun malformedTelegramIdsAreTreatedAsYouTubeIds() {
        assertFalse(LyricsMediaIdPolicy.isTelegramMediaId("telegram://track/abc/1/2"))
        assertFalse(LyricsMediaIdPolicy.isTelegramMediaId("telegram://track/1/2"))
        assertTrue(LyricsMediaIdPolicy.supportsMediaId(simpMusic, "telegram://track/abc/1/2"))
    }

    @Test
    fun blankAndPlainIdsAreNotLocalOrTelegram() {
        assertFalse(LyricsMediaIdPolicy.isNonYouTubeMediaId(""))
        assertFalse(LyricsMediaIdPolicy.isNonYouTubeMediaId(youTubeId))
    }

    @Test
    fun theHelperDoesNotAskSimpMusicAboutATelegramTrack() =
        runTest {
            var simpMusicRequests = 0
            val simpMusicClient = HttpClient(MockEngine { simpMusicRequests++; respond("", HttpStatusCode.NotFound) })
            val helper =
                helperOf(
                    listOf(SimpMusicLyricsProvider(simpMusicClient), answering("LrcLib", PLAIN_TEXT)),
                    backgroundScope,
                )

            assertEquals("LrcLib", helper.getLyricsWithProvider(request(id = telegramId)).providerName)
            assertEquals(0, simpMusicRequests)
        }
}
