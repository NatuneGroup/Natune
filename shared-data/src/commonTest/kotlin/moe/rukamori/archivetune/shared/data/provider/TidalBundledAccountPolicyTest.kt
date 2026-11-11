package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlin.io.encoding.Base64
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.source.*

class TidalBundledAccountPolicyTest {
    private val request = AudioResolveRequest("media", TrackMatching.Target("Song", listOf("Artist"), null, 180_000),
        directIds = mapOf(AudioSourceType.TIDAL to "7"), qualities = mapOf(AudioSourceType.TIDAL to "HI_RES_LOSSLESS"))
    private val track = """{"id":7,"title":"Song","artists":[{"name":"Artist"}],"duration":180}"""
    private val manifest = Base64.encode("<MPD><Period/></MPD>".encodeToByteArray())

    @Test fun accountPolicyDowngrades5003AndKeepsReturnedManifestQuality() = runTest {
        val qualities = mutableListOf<String?>()
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals("Bearer fixture-token", it.headers[HttpHeaders.Authorization])
                if (it.url.encodedPath.endsWith("playbackinfopostpaywall")) {
                    qualities += it.url.parameters["audioquality"]
                    if (qualities.size < 3) respond("""{"subStatus":5003}""", HttpStatusCode.Unauthorized)
                    else respond("""{"manifest":"$manifest","audioQuality":"HIGH","assetPresentation":"FULL"}""")
                } else respond(track)
            }
        } }
        try {
            val repo = KtorTidalRepository(http, TidalAccess("personal", bearer = "fixture-token"), accountQualityFallback = { true })
            val audio = assertIs<PreparedAudio.TidalManifest>(repo.resolve(request))
            assertEquals(listOf<String?>("HI_RES_LOSSLESS", "LOSSLESS", "HIGH"), qualities)
            assertEquals("HIGH", audio.quality)
            assertEquals("personal", audio.accountScope)
        } finally { http.close() }
    }

    @Test fun rateLimitStopsQualityRetriesAndRebuiltAccountRepositoriesUntilCooldown() = runTest {
        var now = 1000L
        var limited = true
        var calls = 0
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls++
                if (it.url.encodedPath.endsWith("playbackinfopostpaywall")) {
                    if (limited) respond("{}", HttpStatusCode.TooManyRequests, headersOf(HttpHeaders.RetryAfter, "10"))
                    else respond("""{"manifest":"$manifest","audioQuality":"LOSSLESS"}""")
                } else respond(track)
            }
        } }
        try {
            val gate = TidalRateLimitGate { now }
            fun repository() = KtorTidalRepository(http, TidalAccess("personal", bearer = "fixture-token"),
                accountQualityFallback = { true }, accountRateLimit = gate)
            val failure = assertFailsWith<TidalPlaybackFailure> { repository().resolve(request) }
            assertEquals(TidalFailureKind.RATE_LIMITED, failure.kind)
            assertEquals(PoolFailureKind.RATE_LIMITED, classifyPoolFailure(failure))
            assertEquals(2, calls)
            assertFailsWith<TidalPlaybackFailure> { repository().resolve(request) }
            assertEquals(2, calls)
            now = 11_000L
            limited = false
            assertEquals("LOSSLESS", assertIs<PreparedAudio.TidalManifest>(repository().resolve(request)).quality)
            assertEquals(4, calls)
        } finally { http.close() }
    }

    @Test fun authRefusalIsNeverDowngradedAndDefaultAccountPathKeepsItsOldFailure() = runTest {
        var qualities = 0
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (it.url.encodedPath.endsWith("playbackinfopostpaywall")) {
                    qualities++
                    respond("""{"subStatus":11003}""", HttpStatusCode.Unauthorized)
                } else respond(track)
            }
        } }
        try {
            val access = TidalAccess("personal", bearer = "fixture-token")
            val enabled = KtorTidalRepository(http, access, accountQualityFallback = { true })
            assertEquals(TidalFailureKind.AUTH_REJECTED, assertFailsWith<TidalPlaybackFailure> { enabled.resolve(request) }.kind)
            assertEquals(1, qualities)
            assertEquals(401, assertFailsWith<ProviderHttpFailure> { KtorTidalRepository(http, access).resolve(request) }.status)
            assertEquals(2, qualities)
        } finally { http.close() }
    }

    @Test fun cancellationDuringPlaybackDoesNotTryAnotherQuality() = runTest {
        val entered = CompletableDeferred<Unit>()
        var qualities = 0
        val http = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (it.url.encodedPath.endsWith("playbackinfopostpaywall")) {
                    qualities++; entered.complete(Unit); awaitCancellation()
                } else respond(track)
            }
        } }
        try {
            val repo = KtorTidalRepository(http, TidalAccess("personal", bearer = "fixture-token"), accountQualityFallback = { true })
            val job = launch { repo.resolve(request) }
            entered.await()
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertEquals(1, qualities)
        } finally { http.close() }
    }
}
