package moe.rukamori.archivetune.desktop.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.settings.*
import moe.rukamori.archivetune.shared.domain.source.*

class DesktopQobuzSourceTest {
    private val request = AudioResolveRequest("song", TrackMatching.Target("Song", listOf("Artist"), null, 180_000),
        directIds = mapOf(AudioSourceType.QOBUZ to "7"), qualities = mapOf(AudioSourceType.QOBUZ to "27"))
    private fun entry(token: String = "fixture-user") =
        QobuzTokenEntry(token, appId = "fixture-app", appSecret = "fixture-secret")
    private fun preferences(vararg tokens: QobuzTokenEntry) = PreferenceSnapshot.EMPTY.toEditor().apply {
        setQobuzTokens(tokens.toList())
        this[UsePoolAccountsKey] = false
    }.snapshot()

    @Test fun personalApiWorksWithPoolOffAndAdmitsOnlyTheProviderVerifiedUser() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        var profiles = 0
        val http = HttpClient(MockEngine { call ->
            calls += call.url.encodedPath.substringAfterLast('/')
            assertEquals("fixture-user", call.headers["X-User-Auth-Token"])
            assertEquals("fixture-app", call.headers["X-App-Id"])
            when {
                call.url.encodedPath.endsWith("user/get") -> {
                    profiles++
                    respond("""{"user":{"id":42}}""")
                }
                call.url.encodedPath.endsWith("getFileUrl") -> {
                    assertEquals("27", call.url.parameters["format_id"])
                    assertFalse(call.url.parameters["request_sig"].isNullOrBlank())
                    respond("""{"url":"https://cdn.invalid/full.flac","mime_type":"audio/flac","sampling_rate":96,"bit_depth":24}""")
                }
                else -> respond("""{"id":7,"title":"Song","performer":{"name":"Artist"},"duration":180}""")
            }
        })
        try {
            var prefs = preferences(entry())
            val admissions = EmptySourceAccounts.admissions()
            val source = DesktopQobuzSource(http, { prefs }, admissions)
            val prepared = assertIs<PreparedAudio.Progressive>(source.resolve(request))
            val scope = assertNotNull(prepared.stream.accountScope)
            val accepted = assertNotNull(admissions.admit("qobuz", scope)).accepted
            assertEquals("42", accepted.identity.accountId)
            assertEquals(CredentialScope.PERSONAL, accepted.identity.scope)
            assertEquals(96_000, prepared.stream.sampleRate)
            assertEquals(listOf("get", "get", "getFileUrl"), calls)
            source.resolve(request)
            assertEquals(1, profiles)
            assertEquals(5, calls.size)
            prefs = preferences()
            assertNull(admissions.admit("qobuz", scope))
            assertEquals(AudioProviderUnavailableReason.ACCOUNT_REQUIRED,
                assertFailsWith<AudioProviderUnavailable> { source.resolve(request) }.reason)
            source.close()
        } finally { http.close() }
    }

    @Test fun savedAccountsKeepTheirOrderAndARejectedCredentialCannotBlockTheNextOne() = runBlocking<Unit> {
        val identities = mutableListOf<String>()
        val http = HttpClient(MockEngine { call ->
            val token = call.headers["X-User-Auth-Token"]
            if (call.url.encodedPath.endsWith("user/get")) {
                identities += token.orEmpty()
                if (token == "first") respond("{}", HttpStatusCode.Unauthorized) else respond("""{"id":42}""")
            } else if (call.url.encodedPath.endsWith("getFileUrl")) {
                respond("""{"url":"https://cdn.invalid/full.flac","mime_type":"audio/flac"}""")
            } else respond("""{"id":7,"title":"Song","performer":{"name":"Artist"},"duration":180}""")
        })
        try {
            val source = DesktopQobuzSource(http, { preferences(entry("first"), entry("second")) },
                EmptySourceAccounts.admissions())
            assertIs<PreparedAudio.Progressive>(source.resolve(request))
            assertEquals(listOf("first", "second"), identities)
        } finally { http.close() }
    }

    @Test fun mismatchedSavedIdentityAndPoolTaggedTokensWithPoolOffCannotObtainAudio() = runBlocking<Unit> {
        var profiles = 0
        val http = HttpClient(MockEngine { call ->
            assertTrue(call.url.encodedPath.endsWith("user/get"))
            profiles++
            respond("""{"id":42}""")
        })
        try {
            val source = DesktopQobuzSource(http, { preferences(entry().copy(userId = "different-user"),
                entry("pooled").copy(poolId = 100)) }, EmptySourceAccounts.admissions())
            assertNull(source.resolve(request))
            assertEquals(1, profiles)
        } finally { http.close() }
    }
}
