/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.provider

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.queue.IncompleteFinitePlaylistException
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType

class ProviderPagingBoundaryTest {
    private val track = ProviderTrack(ContentItem(ContentRef("tidal", ContentKind.SONG, "7"), "Song"),
        emptyList(), null, null, "{}")
    private fun provider(scope: String, body: (Int) -> ProviderTrackPage) = object : PagedAudioProvider(scope) {
        override val provider = ContentProvider.TIDAL
        override val source = AudioSourceType.TIDAL
        override suspend fun tracks(request: ContentRequest, offset: Int, limit: Int) = body(offset)
        override suspend fun track(id: String) = track
        override suspend fun resolve(request: AudioResolveRequest): PreparedAudio? = null
    }
    private val playlist = ContentRequest(ContentProvider.TIDAL, ContentSurface.PLAYLIST,
        parent = ContentRef("tidal", ContentKind.PLAYLIST, "p"))

    @Test fun unknownFiniteTailAndEmptyNonterminalRejectButExplicitZeroSucceeds() = runTest {
        assertFailsWith<IncompleteFinitePlaylistException> {
            provider("account-a") { ProviderTrackPage(listOf(track), null) }.page(playlist)
        }
        assertFailsWith<IllegalArgumentException> {
            provider("account-a") { ProviderTrackPage(emptyList(), 1) }.page(playlist)
        }
        assertNull(provider("account-a") { ProviderTrackPage(emptyList(), 0) }.page(playlist).continuation)
    }
    @Test fun duplicateOrdinalsSurviveAndAccountOrRequestCannotReuseCursor() = runTest {
        val firstProvider = provider("account-a") { ProviderTrackPage(listOf(track), 2) }
        val first = firstProvider.page(playlist)
        val second = firstProvider.page(playlist, first.continuation)
        assertEquals(first.items.single().item, second.items.single().item)
        assertNotEquals(first.items.single().occurrenceId, second.items.single().occurrenceId)
        assertNull(second.continuation)
        assertFailsWith<IllegalArgumentException> {
            provider("account-b") { error("Foreign cursor must fail before transport") }.page(playlist, first.continuation)
        }
        assertFailsWith<IllegalArgumentException> {
            firstProvider.page(playlist.copy(parent = playlist.parent!!.copy(id = "different")), first.continuation)
        }
    }
}
