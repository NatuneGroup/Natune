package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import moe.rukamori.archivetune.shared.domain.repository.AudioResolveRequest
import moe.rukamori.archivetune.shared.domain.repository.PreparedAudio
import moe.rukamori.archivetune.shared.domain.source.AudioSourceType
import moe.rukamori.archivetune.shared.domain.source.TrackMatching

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderStreamCacheRecoveryTest {
    private val request = AudioResolveRequest("media",
        TrackMatching.Target("Song", listOf("Artist"), "Album", 180_000L),
        directIds = mapOf(AudioSourceType.DEEZER to "7"))

    @Test
    fun freshStreamInvalidatesUrlButKeepsTheSeparateAccountSession() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            val old = assertIs<PreparedAudio.DeezerEncrypted>(f.repository.resolve(request))
            assertEquals(old, f.repository.resolve(request))
            assertEquals(1, f.mediaCalls)
            val fresh = assertIs<PreparedAudio.DeezerEncrypted>(
                f.repository.resolve(request.copy(forceFreshStream = true)))
            assertEquals("https://audio.invalid/2?__token__=exp=1060~hmac=fixture", fresh.stream.uri)
            assertEquals(fresh, f.repository.resolve(request))
            assertEquals(2, f.mediaCalls)
            assertEquals(1, f.sessions)
            assertEquals(1_060_000L, fresh.stream.expiresAtEpochMs)
        } finally { f.client.close() }
    }

    @Test
    fun signedWindowShortensProviderTtlAndGuardedUrlsAreNotReused() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.now = 0L
            assertIs<PreparedAudio.DeezerEncrypted>(f.repository.resolve(request))
            f.now = 29_999L
            assertIs<PreparedAudio.DeezerEncrypted>(f.repository.resolve(request))
            assertEquals(1, f.mediaCalls)
            f.now = 30_000L
            assertIs<PreparedAudio.DeezerEncrypted>(f.repository.resolve(request))
            assertEquals(2, f.mediaCalls)
        } finally { f.client.close() }
    }

    @Test
    fun anInflightResultCannotRepopulateTheInvalidatedProviderCache() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        f.beforeMedia = { call -> if (call == 1) { started.complete(Unit); finish.await() } }
        try {
            val stale = async { f.repository.resolve(request) }
            started.await()
            f.repository.invalidate(request.mediaId)
            finish.complete(Unit)
            assertNull(stale.await())
            assertIs<PreparedAudio.DeezerEncrypted>(f.repository.resolve(request))
            assertEquals(2, f.mediaCalls)
            assertIs<PreparedAudio.DeezerEncrypted>(f.repository.resolve(request))
            assertEquals(2, f.mediaCalls)
        } finally { f.client.close() }
    }

    @Test
    fun freshStreamAlsoRetiresANegativeUrlCacheWithoutSigningOut() = runTest {
        val f = Fixture(StandardTestDispatcher(testScheduler))
        try {
            f.trackAvailable = false
            assertNull(f.repository.resolve(request))
            val calls = f.detailsCalls
            f.trackAvailable = true
            assertNull(f.repository.resolve(request))
            assertEquals(calls, f.detailsCalls)
            assertIs<PreparedAudio.DeezerEncrypted>(
                f.repository.resolve(request.copy(forceFreshStream = true)))
            assertEquals(calls + 1, f.detailsCalls)
            assertEquals(1, f.sessions)
        } finally { f.client.close() }
    }

    private class Fixture(engineDispatcher: CoroutineDispatcher) {
        var now = 1_000_000L
        var sessions = 0
        var mediaCalls = 0
        var detailsCalls = 0
        var trackAvailable = true
        var beforeMedia: suspend (Int) -> Unit = {}
        val client = HttpClient(MockEngine) { engine {
            dispatcher = engineDispatcher
            addHandler { request ->
                when {
                    request.url.host == "api.deezer.com" ->
                        respond("""{"id":7,"title":"Song","duration":180,"artist":{"name":"Artist"},"album":{"title":"Album"}}""")
                    request.url.parameters["method"] == "deezer.getUserData" -> {
                        sessions++
                        respond("""{"results":{"checkForm":"fixture","USER":{"USER_ID":1,"OPTIONS":{"license_token":"fixture","web_lossless":true}}}}""")
                    }
                    request.url.parameters["method"] == "song.getData" -> {
                        detailsCalls++
                        respond(if (trackAvailable) """{"results":{"TRACK_TOKEN":"fixture"}}""" else """{"results":{}}""")
                    }
                    else -> {
                        val call = ++mediaCalls
                        beforeMedia(call)
                        val url = "https://audio.invalid/$call?__token__=exp=${(now + 60_000L) / 1_000L}~hmac=fixture"
                        respond("""{"data":[{"media":[{"type":"FULL","format":"FLAC","cipher":"BF_CBC_STRIPE","sources":[{"url":"$url"}]}]}]}""")
                    }
                }
            }
        } }
        val repository = KtorDeezerRepository(client, { now }, DeezerAccess("personal:fixture", "fixture"))
    }
}
