package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*

class DesktopJioSaavnWebPlayerTest {
    @Test fun savedWebPlayerOptionIsReadByTheBuiltInDesktopSource() = runBlocking<Unit> {
        var prefs = PreferenceSnapshot.of(JioSaavnWebPlayerKey to true)
        val contexts = mutableListOf<String?>()
        val http = HttpClient(MockEngine { call ->
            contexts += call.url.parameters["ctx"]
            if (call.url.parameters["__call"] == "song.getDetails") respond(
                """{"songs":[{"id":"7","title":"Song","more_info":{"duration":"180","encrypted_media_url":"fixture-ciphertext","320kbps":"true","artistMap":{"primary_artists":[{"name":"Artist"}]}}}]}""")
            else respond("""{"status":"success","auth_url":"https://cdn.invalid/song_320.mp4"}""")
        })
        try {
            val source = desktopJioSaavnSource(http, { prefs })
            val request = AudioResolveRequest("song", TrackMatching.Target("Song", listOf("Artist"), null, 180_000),
                directIds = mapOf(AudioSourceType.JIOSAAVN to "7"))
            assertEquals("https://cdn.invalid/song_320.mp4", assertIs<PreparedAudio.Progressive>(source.resolve(request)).stream.uri)
            assertEquals(listOf<String?>("web6dot0", "web6dot0"), contexts)
            prefs = PreferenceSnapshot.EMPTY
            assertFailsWith<Exception> { source.resolve(request) } // fixture is not valid DES ciphertext
            assertEquals(listOf<String?>("web6dot0", "web6dot0", "android"), contexts)
        } finally { http.close() }
    }
}
