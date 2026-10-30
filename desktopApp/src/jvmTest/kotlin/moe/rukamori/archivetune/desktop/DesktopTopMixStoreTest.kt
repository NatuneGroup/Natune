package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.data.provider.PERSONAL_MIX_LIMIT
import moe.rukamori.archivetune.shared.data.provider.PERSONAL_MIX_SONG_LIMIT
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeed
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedItem
import moe.rukamori.archivetune.spotify.models.SpotifyHomeFeedSection

class DesktopTopMixStoreTest {
    private fun record(id: String, songs: List<String> = listOf("s1")) =
        DesktopMixRecord(id, "Mix $id", "About $id", songs)

    @Test
    fun manifestRoundTrips() {
        val manifest = DesktopMixManifest(123L, listOf(record("m1"), record("m2", listOf("a", "b"))))
        assertEquals(manifest, decodeDesktopMixManifest(encodeDesktopMixManifest(manifest)))
    }

    @Test
    fun manifestRejectsInvalidShapes() {
        assertFailsWith<IllegalArgumentException> { encodeDesktopMixManifest(DesktopMixManifest(-1L, emptyList())) }
        assertFailsWith<IllegalArgumentException> { encodeDesktopMixManifest(DesktopMixManifest(0L, listOf(record("m", emptyList())))) }
        assertFailsWith<IllegalArgumentException> {
            encodeDesktopMixManifest(DesktopMixManifest(0L, (1..PERSONAL_MIX_LIMIT + 1).map { record("m$it") }))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeDesktopMixManifest(DesktopMixManifest(0L, listOf(record("m", (1..PERSONAL_MIX_SONG_LIMIT + 1).map { "s$it" }))))
        }
        assertFailsWith<Exception> { decodeDesktopMixManifest("[]") }
        assertFailsWith<Exception> { decodeDesktopMixManifest("""{"generatedAt":-1,"mixes":[]}""") }
        assertFailsWith<Exception> { decodeDesktopMixManifest("""{"generatedAt":1,"mixes":[{"id":"m","title":"t","songs":[]}]}""") }
        assertFailsWith<Exception> {
            decodeDesktopMixManifest("""{"generatedAt":1,"mixes":[{"id":"m","title":"t","songs":["a"]},{"id":"m","title":"u","songs":["b"]}]}""")
        }
        assertFailsWith<Exception> { decodeDesktopMixManifest("""{"generatedAt":1,"mixes":[{"id":"m","title":"t","songs":["a",7]}]}""") }
    }

    @Test
    fun comparableNamesFoldCaseAndPunctuationConservatively() {
        assertTrue(comparableMixName("Song (Live)", "song"))
        assertTrue(comparableMixName("HELLO!", "hello"))
        assertFalse(comparableMixName("abc", "abcd"))
        assertFalse(comparableMixName("", "x"))
        assertFalse(comparableMixName("Beyoncé", "beyonce"))
    }

    @Test
    fun onlyServiceMarkedSpotifyMixesQualify() {
        fun playlist(id: String, madeFor: String? = null, format: String? = null) = SpotifyHomeFeedItem.Playlist(
            uri = "spotify:playlist:$id", id = id, name = "Mix $id", description = null, format = format,
            totalCount = 0, imageUrl = null, extractedColorHex = null, ownerName = null, madeForUsername = madeFor,
        )
        val feed = SpotifyHomeFeed(
            null,
            listOf(
                SpotifyHomeFeedSection(
                    "spotify:section:made-for-you", "Made for you", "HomeGenericSection", 4,
                    listOf(
                        playlist("a", madeFor = "me"),
                        playlist("b", format = "mix"),
                        playlist("c"),
                        playlist("a", madeFor = "me"),
                    ),
                ),
            ),
        )
        val rows = desktopSpotifyMixRows(feed)
        assertEquals(listOf("spotify-mix:a", "spotify-mix:b"), rows.map { it.occurrenceId })
        assertEquals(ContentProvider.SPOTIFY_CATALOG.id, rows[0].item.ref.provider)
        assertEquals(ContentKind.PLAYLIST, rows[0].item.ref.kind)
    }
}
