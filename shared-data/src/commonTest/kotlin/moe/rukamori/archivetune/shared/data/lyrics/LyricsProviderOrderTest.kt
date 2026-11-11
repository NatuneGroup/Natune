package moe.rukamori.archivetune.shared.data.lyrics

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsProviderOrderTest {
    private fun client(): HttpClient = HttpClient(MockEngine { throw CancellationException("unused by ordering tests") })

    @Test
    fun canonicalNamesFollowTheAndroidLyricsHelperBaseProviderOrder() {
        // Android LyricsHelper.baseProviders, restricted to the providers ported here:
        // BetterLyrics, BetterLyrics Portato, YouLyPlus, LrcLib, Kugou, SimpMusic, Unison,
        // Paxsenix Apple Music, Paxsenix NetEase, Paxsenix Spotify, Paxsenix Musixmatch, Paxsenix YouTube.
        // The bundled Lyprio server leads, as in Metrolist's default order.
        assertEquals(
            listOf(
                "Lyprio",
                "BetterLyrics",
                "BetterLyrics Portato",
                "YouLyPlus",
                "LrcLib",
                "Kugou",
                "SimpMusic",
                "Unison",
                "Paxsenix: Apple Music",
            ),
            LyricsProviderOrder.CANONICAL_NAMES,
        )
    }

    @Test
    fun theShippedProviderClassesCarryTheCanonicalNames() {
        val client = client()
        val lyprio = checkNotNull(LyprioEndpoint.parse("https://lyrics.example.invalid", "A".repeat(43) + "="))
        val providers =
            listOf(
                LyprioLyricsProvider(client, lyprio),
                BetterLyricsProvider(client),
                BetterLyricsProvider.portato(client),
                YouLyPlusLyricsProvider(client),
                LrcLibLyricsProvider(client),
                KuGouLyricsProvider(client),
                SimpMusicLyricsProvider(client),
                UnisonLyricsProvider(client),
                PaxsenixAppleMusicLyricsProvider(client),
        )

        assertEquals(LyricsProviderOrder.CANONICAL_NAMES, providers.map { it.name })
    }

    @Test
    fun sortCanonicalRestoresTheCanonicalOrderFromAShuffledList() {
        val client = client()
        val shuffled =
            listOf(
                UnisonLyricsProvider(client),
                LrcLibLyricsProvider(client),
                BetterLyricsProvider(client),
            )

        assertEquals(
            listOf("BetterLyrics", "LrcLib", "Unison"),
            LyricsProviderOrder.sortCanonical(shuffled).map { it.name },
        )
    }

    @Test
    fun unknownProvidersKeepTheirInputOrderAfterTheCanonicalOnes() {
        val client = client()
        val unknownFirst = answering("Zeta", PLAIN_TEXT)
        val unknownSecond = answering("Alpha", PLAIN_TEXT)

        val sorted =
            LyricsProviderOrder.sortCanonical(
                listOf(unknownFirst, UnisonLyricsProvider(client), unknownSecond, LrcLibLyricsProvider(client)),
            )

        assertEquals(listOf("LrcLib", "Unison", "Zeta", "Alpha"), sorted.map { it.name })
    }

    @Test
    fun wordSyncSweepNamesAreTheThreeAndroidSweepProvidersInCanonicalOrder() {
        val sweep = LyricsProviderOrder.WORD_SYNC_NAMES
        assertEquals(listOf("BetterLyrics", "YouLyPlus", "Unison"), sweep)
        val positions = sweep.map { LyricsProviderOrder.CANONICAL_NAMES.indexOf(it) }
        assertTrue(positions == positions.sorted(), "sweep names must follow the canonical order")
    }
}
