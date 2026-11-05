package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*

class DesktopPersonalSourcesTest {
    private val request = AudioResolveRequest("song", TrackMatching.Target("Song", listOf("Artist"), null, 180_000))

    @Test fun absentAndExpiredAmazonAuthorizationNeverMakesAnAnonymousResolverRequest() = runBlocking<Unit> {
        val http = HttpClient(MockEngine { error("unconfigured source must not contact the instance") })
        try {
            var prefs = PreferenceSnapshot.of(AmazonInstancesKey to "https://first.invalid")
            val source = DesktopAmazonSource(http, { prefs }, { 1000 })
            assertEquals(AudioProviderUnavailableReason.ACCOUNT_REQUIRED,
                assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }.reason)
            prefs = prefs.toEditor().apply {
                this[AmazonTurnstileJwtKey] = "expired"
                this[AmazonTurnstileJwtExpiryMsKey] = 1000L
            }.snapshot()
            assertNull(desktopAmazonAuthorization(prefs, 1000))
            assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }
        } finally { http.close() }
    }

    @Test fun jwtStaysOnPrimaryInstanceWhileOperatorCanFallThroughToTheNextConfiguredHost() = runBlocking<Unit> {
        val seen = mutableListOf<Pair<String, String?>>()
        val http = HttpClient(MockEngine { call ->
            seen += call.url.host to call.headers["X-Turnstile-JWT"]
            if (call.url.host == "first.invalid") respond("{}", HttpStatusCode.Forbidden)
            else {
                assertEquals("Bearer fixture-operator", call.headers["Authorization"])
                respond("""{"schema_version":"2","track":{"title":"Song","artist":"Artist","duration_ms":180000},"playback":[{"kind":"audio","delivery":"direct","url":"https://cdn.invalid/full","codec":"aac"}]}""")
            }
        })
        try {
            var prefs = PreferenceSnapshot.of(AmazonInstancesKey to "https://first.invalid\nhttps://second.invalid",
                AmazonTurnstileJwtKey to "primary-jwt", AmazonTurnstileJwtExpiryMsKey to 100_000L)
            val source = DesktopAmazonSource(http, { prefs }, { 1000 })
            assertFailsWith<Exception> { source.resolve(request) }
            assertEquals(listOf<Pair<String, String?>>("first.invalid" to "primary-jwt"), seen)
            seen.clear()
            prefs = prefs.toEditor().apply { this[AmazonBypassTokenKey] = "fixture-operator" }.snapshot()
            val audio = assertIs<PreparedAudio.Progressive>(source.resolve(request))
            assertNull(audio.stream.accountScope)
            assertEquals(listOf("first.invalid" to "primary-jwt", "first.invalid" to "primary-jwt",
                "second.invalid" to null), seen)
        } finally { http.close() }
    }

    @Test fun appleEncryptedHlsIsUnavailableAndNeverAnOrdinaryProgressiveUrl() = runBlocking<Unit> {
        val seen = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            seen += call.url.encodedPath
            when {
                call.url.encodedPath.endsWith("storefront") -> respond("""{"data":[{"attributes":{"name":"us"}}]}""")
                call.url.encodedPath.endsWith("webPlayback") -> respond(
                    """{"songList":[{"assets":[{"flavor":"ctrp256","URL":"https://cdn.invalid/protected.m3u8"}]}]}""")
                call.url.host == "cdn.invalid" -> {
                    assertNull(call.headers["Media-User-Token"])
                    respond("#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES\nprotected.m4a")
                }
                else -> respond("""{"data":[{"id":"7","attributes":{"name":"Song","artistName":"Artist","durationInMillis":180000}}]}""")
            }
        })
        try {
            var prefs = PreferenceSnapshot.EMPTY
            val source = DesktopAppleMusicSource(http, { prefs })
            assertEquals(AudioProviderUnavailableReason.ACCOUNT_REQUIRED,
                assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }.reason)
            assertTrue(seen.isEmpty())
            prefs = PreferenceSnapshot.of(AppleMusicDevTokenKey to "developer", AppleMusicMediaUserTokenKey to "user")
            assertEquals(AudioProviderUnavailableReason.PLATFORM_DECRYPTION_REQUIRED,
                assertFailsWith<AudioProviderUnavailable> {
                    source.resolve(request.copy(directIds = mapOf(AudioSourceType.APPLE to "7")))
                }.reason)
            assertTrue(seen.any { it.endsWith("protected.m3u8") })
        } finally { http.close() }
    }
}
