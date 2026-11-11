package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class JioSaavnWebPlayerTest {
    private fun request(quality: String = "320kbps") = AudioResolveRequest("media",
        TrackMatching.Target("Song", listOf("Artist"), "Album", 180_000),
        directIds = mapOf(AudioSourceType.JIOSAAVN to "7"),
        qualities = mapOf(AudioSourceType.JIOSAAVN to quality))
    private fun song(high: Boolean = true, rights: Int = 0) =
        """{"id":"7","title":"Song","more_info":{"album":"Album","duration":"180","encrypted_media_url":"fixture-ciphertext","320kbps":"$high","rights":{"code":"$rights"},"artistMap":{"primary_artists":[{"name":"Artist"}]}}}"""

    @Test fun keyFree320ExchangeAcceptsKeyedDetailsAndCarriesRefererAndExpiry() = runTest {
        val calls = mutableListOf<String>()
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals("web6dot0", it.url.parameters["ctx"])
                assertEquals("https://www.jiosaavn.com/", it.headers[HttpHeaders.Referrer])
                assertNull(it.headers[HttpHeaders.Cookie])
                calls += it.url.parameters["__call"].orEmpty()
                when (it.url.parameters["__call"]) {
                    "song.getDetails" -> respond("""{"7":${song()}}""")
                    "song.generateAuthToken" -> {
                        assertEquals("fixture-ciphertext", it.url.parameters["url"])
                        assertEquals("320", it.url.parameters["bitrate"])
                        respond("""{"status":"success","auth_url":"https://cdn.invalid/song_320.mp4?Expires=2000000000"}""")
                    }
                    else -> error("unexpected request")
                }
            }
        } }
        try {
            val stream = assertIs<PreparedAudio.Progressive>(KtorJioSaavnRepository(http).resolve(request())).stream
            assertEquals("https://cdn.invalid/song_320.mp4?Expires=2000000000", stream.uri)
            assertEquals("audio/mp4", stream.mimeType)
            assertEquals("mp4a.40.2", stream.codecs)
            assertEquals(2_000_000_000_000L, stream.expiresAtEpochMs)
            assertEquals(mapOf("Referer" to "https://www.jiosaavn.com/"), stream.headers.reveal())
            assertNull(stream.accountScope)
            assertEquals(listOf("song.getDetails", "song.generateAuthToken"), calls)
        } finally { http.close() }
    }

    @Test fun unavailable320AndLowerQualityChoicesMint96WithoutInventingCdnVariants() = runTest {
        var high = false
        val bitrates = mutableListOf<String>()
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (it.url.parameters["__call"] == "song.getDetails") respond("""{"songs":[${song(high)}]}""")
                else {
                    val bitrate = it.url.parameters["bitrate"].orEmpty()
                    bitrates += bitrate
                    respond("""{"status":"success","auth_url":"https://cdn.invalid/minted_${bitrate}.mp4"}""")
                }
            }
        } }
        try {
            val repo = KtorJioSaavnRepository(http)
            for (quality in listOf("320kbps", "96kbps", "160kbps")) {
                if (quality != "320kbps") high = true
                assertEquals("https://cdn.invalid/minted_96.mp4",
                    assertIs<PreparedAudio.Progressive>(repo.resolve(request(quality))).stream.uri)
            }
            assertEquals(listOf("96", "96", "96"), bitrates)
        } finally { http.close() }
    }

    @Test fun explicitOptionSwitchesFromRetainedDecoderToWebExchange() = runTest {
        var web = false
        var decodes = 0
        var exchanges = 0
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals(if (web) "web6dot0" else "android", it.url.parameters["ctx"])
                if (it.url.parameters["__call"] == "song.getDetails") respond("""{"songs":[${song()}]}""")
                else { exchanges++; respond("""{"status":"success","auth_url":"https://cdn.invalid/web_320.mp4"}""") }
            }
        } }
        try {
            val repo = KtorJioSaavnRepository(http, JioSaavnUrlDecoder { decodes++; "https://cdn.invalid/old_96.mp4" },
                useWebPlayer = { web })
            assertEquals("https://cdn.invalid/old_320.mp4", assertIs<PreparedAudio.Progressive>(repo.resolve(request())).stream.uri)
            web = true
            assertEquals("https://cdn.invalid/web_320.mp4", assertIs<PreparedAudio.Progressive>(repo.resolve(request())).stream.uri)
            assertEquals(1, decodes)
            assertEquals(1, exchanges)
        } finally { http.close() }
    }

    @Test fun rejectedWrongQualityAndInsecureAuthUrlsNeverBecomePreparedAudio() = runTest {
        var reply = ""
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (it.url.parameters["__call"] == "song.getDetails") respond("""{"songs":[${song()}]}""")
                else respond(reply)
            }
        } }
        try {
            val repo = KtorJioSaavnRepository(http)
            for ((status, url) in listOf("failure" to "https://cdn.invalid/song_320.mp4",
                "success" to "https://cdn.invalid/song_96.mp4", "success" to "http://cdn.invalid/song_320.mp4",
                "success" to "https://cdn.invalid/preview.mp4?name=song_320.mp4")) {
                reply = """{"status":"$status","auth_url":"$url"}"""
                if (url.startsWith("http://")) {
                    assertFailsWith<IllegalArgumentException> { repo.resolve(request()) }
                } else {
                    assertFailsWith<ProviderProtocolFailure> { repo.resolve(request()) }
                }
            }
        } finally { http.close() }
    }

    @Test fun webSearchDecodesEntitiesAndKeepsFeaturedArtistForRecordingMatch() = runTest {
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                when (it.url.parameters["__call"]) {
                    "search.getResults" -> respond("""{"results":[{"id":"7","title":"Song &amp; More","more_info":{"album":"Album","duration":"180","encrypted_media_url":"cipher","320kbps":"true","artistMap":{"primary_artists":[{"name":"Primary"}],"featured_artists":[{"name":"Artist"}]}}}],"total":1}""")
                    "song.generateAuthToken" -> respond("""{"status":"success","auth_url":"https://cdn.invalid/song_320.mp4"}""")
                    else -> error("unexpected request")
                }
            }
        } }
        try {
            val stream = assertIs<PreparedAudio.Progressive>(KtorJioSaavnRepository(http).resolve(request().copy(
                target = TrackMatching.Target("Song & More", listOf("Artist"), "Album", 180_000), directIds = emptyMap()))).stream
            assertEquals("Song & More", stream.matchedTitle)
            assertEquals("Primary, Artist", stream.matchedArtist)
        } finally { http.close() }
    }

    @Test fun keyFreeAuthExchangePreservesCallerCancellation() = runTest {
        val entered = CompletableDeferred<Unit>()
        var exchanged = false
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (it.url.parameters["__call"] == "song.getDetails") respond("""{"songs":[${song()}]}""")
                else { entered.complete(Unit); awaitCancellation() }
            }
        } }
        try {
            val job = launch { KtorJioSaavnRepository(http).resolve(request()); exchanged = true }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertFalse(exchanged)
        } finally { http.close() }
    }
}
