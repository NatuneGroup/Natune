package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlin.test.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.platform.ANONYMOUS_ACCOUNT_SCOPE
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class AmazonSourcePickResolutionTest {
    @Test fun selectedMetadataIsSentToTheInstanceAndReturnedMetadataIsStillVerified() = runTest {
        var responseTitle = "Selected different"
        // Run the handler on the test scheduler: a Default-dispatcher handler lets runTest's virtual clock reach the 12 s provider timeout first.
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                assertEquals("/api/track/", request.url.encodedPath)
                assertEquals("Selected different", request.url.parameters["track"])
                assertEquals("Chosen artist", request.url.parameters["artist"])
                assertEquals("Chosen album", request.url.parameters["album"])
                assertEquals("191", request.url.parameters["duration"])
                assertEquals("UHD", request.url.parameters["quality"])
                respond("""{"stream_url":"https://audio.invalid/full.m4a","title":"$responseTitle","artist":"Chosen artist","album":"Chosen album","duration_ms":190501}""")
            }
        } }
        try {
            val repository = KtorAmazonRepository(client, "https://instance.invalid",
                AmazonInstanceAccess(ANONYMOUS_ACCOUNT_SCOPE, null, "fixture-jwt", 100_000) { true }, { 1000L })
            val request = AudioResolveRequest("original", TrackMatching.Target("Original name", listOf("Original artist"),
                null, 180_000), qualities = mapOf(AudioSourceType.AMAZON to "ULTRA_HD"),
                amazonPick = AmazonTrackPick("Selected different", "Chosen artist", "Chosen album", 190_501))
            val selected = assertIs<PreparedAudio.Progressive>(repository.resolve(request))
            assertFalse(selected.stream.trustedDirectId)
            assertTrue(selected.matchesRequest(request))
            assertFalse(selected.matchesRequest(request.copy(amazonPick = null)))
            responseTitle = "Unrelated recording"
            val mismatched = assertIs<PreparedAudio.Progressive>(repository.resolve(request))
            assertFalse(mismatched.matchesRequest(request))
        } finally { client.close() }
    }
}
