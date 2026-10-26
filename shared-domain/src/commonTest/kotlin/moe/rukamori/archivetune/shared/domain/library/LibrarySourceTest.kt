package moe.rukamori.archivetune.shared.domain.library

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the rule every Library section reads its source through. The Library hands its sections one resolved source,
 * so this decides what all of them read at once. Spotify resolves to YTM while no session is signed in, which keeps the
 * mixes, playlists and artists rails off an account that is not there.
 */
class LibrarySourceTest {
    @Test
    fun unavailableSpotifyReportsYtm() {
        assertEquals(LibrarySource.YTM, LibrarySource.SPOTIFY.resolved(spotifyAvailable = false))
    }

    @Test
    fun availableSpotifyIsHonoured() {
        assertEquals(LibrarySource.SPOTIFY, LibrarySource.SPOTIFY.resolved(spotifyAvailable = true))
    }

    @Test
    fun ytmIsUnaffected() {
        assertEquals(LibrarySource.YTM, LibrarySource.YTM.resolved(spotifyAvailable = false))
    }

    @Test
    fun theLibraryStartsOnYtm() {
        assertEquals(LibrarySource.YTM, LibraryDefaults.source)
    }
}
