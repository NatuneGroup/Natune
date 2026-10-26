/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class JioSaavnProtocolTest {
    @Test fun directDetailsUseRetainedWireMetadataAndQualityWithoutForgedIpHeaders() = runTest {
        var pro = false
        var decodes = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals("song.getDetails", it.url.parameters["__call"])
                assertEquals("7", it.url.parameters["pids"])
                assertEquals("android", it.url.parameters["ctx"])
                assertEquals("explicit_content=1", it.headers[HttpHeaders.Cookie])
                assertNull(it.headers["X-Forwarded-For"])
                respond("""{"songs":[{"id":"7","title":"Song","explicit_content":"1","image":"https://images.invalid/150x150","more_info":{"album":"Album","duration":"180","encrypted_media_url":"fixture-ciphertext","rights":{"code":"${if (pro) 1 else 0}"},"artistMap":{"primary_artists":[{"name":"Artist"}]}}}]}""")
            }
        } }
        try {
            val repo = KtorJioSaavnRepository(client, JioSaavnUrlDecoder { decodes++; "https://audio.invalid/song_96.mp4" })
            val request = AudioResolveRequest("media", TrackMatching.Target("Song", listOf("Artist"), "Album", 180_000),
                mapOf(AudioSourceType.JIOSAAVN to "7"), qualities = mapOf(AudioSourceType.JIOSAAVN to "160kbps"))
            val audio = assertIs<PreparedAudio.Progressive>(repo.resolve(request))
            assertEquals("https://audio.invalid/song_160.mp4", audio.stream.uri)
            assertEquals(180_000, audio.stream.matchedDurationMs)
            pro = true
            assertNull(repo.resolve(request))
            assertEquals(1, decodes)
        } finally { client.close() }
    }
}
