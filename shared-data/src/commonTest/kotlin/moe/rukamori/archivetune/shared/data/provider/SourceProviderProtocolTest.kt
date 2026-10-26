package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlin.io.encoding.Base64
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import moe.rukamori.archivetune.shared.domain.repository.*
import moe.rukamori.archivetune.shared.domain.platform.CredentialScope
import moe.rukamori.archivetune.shared.domain.source.*

/** Synthetic protocol fixtures characterized from the retained Android requests, NOT live accounts. */
class SourceProviderProtocolTest {
    private val target = TrackMatching.Target("Song", listOf("Artist"), "Album", 180_000)
    private fun request(source: AudioSourceType? = null, id: String = "7") =
        AudioResolveRequest("media", target, source?.let { mapOf(it to id) }.orEmpty())
    private val tidalTrack = """{"id":7,"title":"Song","duration":180,"artists":[{"name":"Artist"}],"album":{"title":"Album","cover":"a-b"},"isrc":"FIXTURE","explicit":true}"""
    private val qobuzTrack = """{"id":7,"title":"Song","duration":180,"performer":{"name":"Artist"},"album":{"title":"Album","image":{"large":"https://images.invalid/a"}}}"""
    private val deezerTrack = """{"id":7,"title":"Song","duration":180,"artist":{"name":"Artist"},"album":{"title":"Album","cover_big":"https://images.invalid/a"}}"""

    @Test fun tidalAccountRequestsFullAssetAndParsesProgressiveBtsWithoutQualityGuesses() = runTest {
        val manifest = Base64.encode("""{"urls":["https://audio.invalid/track","https://alternate.invalid/full-file"],"mimeType":"audio/flac","codecs":"flac","sampleRate":96000,"bitDepth":24,"encryptionType":"NONE"}""".encodeToByteArray())
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals("Bearer fixture-bearer", it.headers[HttpHeaders.Authorization])
                if (it.url.encodedPath.endsWith("playbackinfopostpaywall")) {
                    assertEquals("FULL", it.url.parameters["assetpresentation"])
                    assertEquals("STREAM", it.url.parameters["playbackmode"])
                    respond("""{"assetPresentation":"FULL","manifest":"$manifest"}""")
                } else respond(tidalTrack)
            }
        } }
        try {
            val repo = KtorTidalRepository(client, TidalAccess("personal:fixture:1", "fixture-bearer"))
            val audio = assertIs<PreparedAudio.Progressive>(repo.resolve(request(AudioSourceType.TIDAL)))
            assertEquals(96_000, audio.stream.sampleRate)
            assertEquals(24, audio.stream.bitDepth)
            assertEquals("https://audio.invalid/track", audio.stream.uri)
            assertTrue(audio.stream.trustedDirectId)
            assertEquals(180_000, audio.stream.matchedDurationMs)
            assertEquals("https://resources.tidal.com/images/a/b/640x640.jpg", repo.track("7").item.artworkUri)
        } finally { client.close() }
    }
    @Test fun tidalNewDialect404UsesOldDialectAndRetainsDashAsManifestNotProgressive() = runTest {
        val paths = mutableListOf<String>()
        val mpd = """<MPD xmlns="urn:mpeg:dash:schema:mpd:2011"><Period/></MPD>"""
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                paths += it.url.encodedPath
                when {
                    it.url.encodedPath.endsWith("/search/") -> respond("""{"data":{"items":[$tidalTrack],"total":1}}""")
                    it.url.encodedPath.endsWith("/trackManifests/") -> {
                        assertEquals("FLAC", it.url.parameters["formats"])
                        assertEquals("false", it.url.parameters["adaptive"])
                        respond("missing", HttpStatusCode.NotFound)
                    }
                    else -> respond("""{"data":{"assetPresentation":"FULL","manifest":"${Base64.encode(mpd.encodeToByteArray())}"}}""")
                }
            }
        } }
        try {
            val audio = KtorTidalRepository(client, TidalAccess("public"), "https://instance.invalid").resolve(request())
            assertEquals(mpd, assertIs<PreparedAudio.TidalManifest>(audio).manifestText)
            assertEquals(listOf("/search/", "/trackManifests/", "/track/"), paths)
        } finally { client.close() }
    }
    @Test fun tidalNewDialectRejectsPreviewBeforeFetchingManifestUri() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls++
                if (calls == 1) respond("""{"items":[$tidalTrack],"total":1}""")
                else respond("""{"data":{"data":{"attributes":{"trackPresentation":"PREVIEW","formats":["FLAC"],"uri":"https://audio.invalid/preview"}}}}""")
            }
        } }
        try {
            assertNull(KtorTidalRepository(client, TidalAccess("public"), "https://instance.invalid").resolve(request()))
            assertEquals(2, calls)
        } finally { client.close() }
    }
    @Test fun qobuzExactSignatureHeadersAndActualSampleRateArePreserved() = runTest {
        var signed = ""
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertEquals("fixture-app", it.headers["X-App-Id"])
                assertEquals("fixture-user", it.headers["X-User-Auth-Token"])
                if (it.url.encodedPath.endsWith("getFileUrl")) {
                    assertEquals("123", it.url.parameters["request_ts"])
                    assertEquals("fixture-digest", it.url.parameters["request_sig"])
                    assertEquals("6", it.url.parameters["format_id"])
                    respond("""{"url":"https://audio.invalid/a.flac","mime_type":"audio/flac","sampling_rate":44.1,"bit_depth":16,"sample":false}""")
                } else respond(qobuzTrack)
            }
        } }
        try {
            val repo = KtorQobuzRepository(client, { 123_000 }, QobuzAccess("personal:1", "fixture-app", "fixture-user", "fixture-secret"),
                QobuzRequestDigest { signed = it; "fixture-digest" })
            val audio = assertIs<PreparedAudio.Progressive>(repo.resolve(request(AudioSourceType.QOBUZ)))
            assertEquals("trackgetFileUrlformat_id6intentstreamtrack_id7123fixture-secret", signed)
            assertEquals(44_100, audio.stream.sampleRate)
            assertEquals(16, audio.stream.bitDepth)
        } finally { client.close() }
    }
    @Test fun qobuzInstanceProtocolAndNestedPreviewDoNotReturnThirtySecondClips() = runTest {
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertNull(it.headers["X-User-Auth-Token"])
                if (it.url.encodedPath.endsWith("get-music")) {
                    assertEquals("0", it.url.parameters["offset"])
                    respond("""{"data":{"tracks":{"items":[$qobuzTrack],"total":1}}}""")
                } else {
                    assertEquals("7", it.url.parameters["track_id"])
                    assertEquals("6", it.url.parameters["quality"])
                    respond("""{"data":{"url":"https://audio.invalid/sample.flac","sample":true}}""")
                }
            }
        } }
        try { assertNull(KtorQobuzRepository(client, { 0 }, instanceBase = "https://instance.invalid").resolve(request())) }
        finally { client.close() }
    }
    @Test fun deezerPublicPagingPreservesDuplicatesAndRejectsForeignQueryAndAccountTokens() = runTest {
        val indexes = mutableListOf<String?>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { indexes += it.url.parameters["index"]; respond("""{"data":[$deezerTrack,$deezerTrack],"total":4}""") }
        } }
        try {
            val repo = KtorDeezerRepository(client, { 0 })
            val query = ContentRequest(ContentProvider.DEEZER, ContentSurface.SEARCH, "Song")
            val first = repo.page(query)
            val second = repo.page(query, first.continuation)
            assertEquals(listOf<String?>("0", "2"), indexes)
            assertNull(second.continuation)
            assertEquals(4, (first.items + second.items).map { it.occurrenceId }.toSet().size)
            assertEquals(1, (first.items + second.items).map { it.item.ref.id }.toSet().size)
            assertFailsWith<IllegalArgumentException> { repo.page(query.copy(text = "Other"), first.continuation) }
            assertFailsWith<IllegalArgumentException> {
                KtorDeezerRepository(client, { 0 }, DeezerAccess("different-account", "fixture-arl")).page(query, first.continuation)
            }
        } finally { client.close() }
    }
    @Test fun deezerEntitlementNotPoolHintControlsFormatAndEncryptedBytesStayTyped() = runTest {
        var sessions = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                if (it.url.host == "api.deezer.com") respond(deezerTrack)
                else {
                    assertEquals("arl=fixture-arl", it.headers[HttpHeaders.Cookie])
                    when (it.url.parameters["method"]) {
                        "deezer.getUserData" -> { sessions++; respond("""{"error":{},"results":{"checkForm":"fixture-form","USER":{"USER_ID":1,"OPTIONS":{"license_token":"fixture-license","web_lossless":false,"mobile_lossless":false}}}}""") }
                        "song.getData" -> respond("""{"error":[],"results":{"TRACK_TOKEN":"fixture-track","FILESIZE_MP3_128":"12345"}}""")
                        else -> {
                            val body = Json.parseToJsonElement((it.body as TextContent).text).jsonObject
                            assertEquals("fixture-license", body["license_token"]?.jsonPrimitive?.content)
                            val formats = body.getValue("media").jsonArray[0].jsonObject.getValue("formats").jsonArray
                            assertEquals(listOf("MP3_320", "MP3_128"), formats.map { it.jsonObject["format"]?.jsonPrimitive?.content })
                            assertTrue(formats.all { it.jsonObject["cipher"]?.jsonPrimitive?.content == "BF_CBC_STRIPE" })
                            respond("""{"data":[{"media":[{"type":"FULL","format":"MP3_128","cipher":"BF_CBC_STRIPE","sources":[{"url":"https://audio.invalid/encrypted"}]}]}]}""")
                        }
                    }
                }
            }
        } }
        try {
            val repo = KtorDeezerRepository(client, { 0 }, DeezerAccess("pool:fixture", "fixture-arl"))
            repeat(2) {
                val audio = assertIs<PreparedAudio.DeezerEncrypted>(repo.resolve(request(AudioSourceType.DEEZER)))
                assertEquals("audio/mpeg", audio.stream.mimeType)
                assertEquals(12345, audio.stream.contentLength)
                assertEquals("pool:fixture", audio.keyScope)
            }
            assertEquals(1, sessions)
        } finally { client.close() }
    }
    @Test fun deezerHttp200UserZeroIsNotASignedInSession() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { calls++; respond("""{"results":{"USER":{"USER_ID":0}}}""") }
        } }
        try {
            assertEquals(401, assertFailsWith<ProviderHttpFailure> {
                KtorDeezerRepository(client, { 0 }, DeezerAccess("personal", "fixture-arl")).resolve(request())
            }.status)
            assertEquals(1, calls)
        } finally { client.close() }
    }
    @Test fun backupSkipsJsonMirrorAndReadsBoundedRangeWithActualFlacMetadata() = runTest {
        val prefix = ByteArray(42)
        "fLaC".encodeToByteArray().copyInto(prefix)
        prefix[7] = 34
        val packed = (96_000L shl 44) or (1L shl 41) or (23L shl 36) or 123456L
        for (i in 0..7) prefix[18 + i] = (packed ushr ((7 - i) * 8)).toByte()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                when (it.url.host) {
                    "mls.kouzu.in" -> {
                        assertEquals("muzo", it.headers["x-request-source"])
                        respond("""{"lossless":"https://bad.invalid/absent","url":"https://audio.invalid/good"}""")
                    }
                    "bad.invalid" -> respond("""{"detail":"Not Found"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> {
                        assertEquals("bytes=0-41", it.headers[HttpHeaders.Range])
                        respond(prefix, HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentType to listOf("application/octet-stream"),
                            HttpHeaders.ContentRange to listOf("bytes 0-41/45678"), HttpHeaders.ContentLength to listOf("42")))
                    }
                }
            }
        } }
        try {
            val audio = assertIs<PreparedAudio.Progressive>(KtorQobuzBackupRepository(client).resolve(request(AudioSourceType.QOBUZ_BACKUP, "abcdefghijk")))
            assertEquals("audio/flac", audio.stream.mimeType)
            assertEquals(96_000, audio.stream.sampleRate)
            assertEquals(24, audio.stream.bitDepth)
            assertEquals(45678, audio.stream.contentLength)
        } finally { client.close() }
    }
    @Test fun backupSearchUsesRealArraySchemaSkipsInvalidRowsAndNeverInventsPaging() = runTest {
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond("""[{"id":"bad","name":"Bad"},{"id":"abcdefghijk","name":"Song","artists":"Artist","lossless":"4:flac"}]""") }
        } }
        try {
            val repo = KtorQobuzBackupRepository(client)
            val query = ContentRequest(ContentProvider.QOBUZ_BACKUP, ContentSurface.SEARCH, "Song")
            assertEquals("abcdefghijk", repo.page(query).items.single().item.ref.id)
            assertFailsWith<IllegalArgumentException> { repo.page(query, "pretend-next-page") }
            assertFailsWith<UnsupportedContentOperation> { repo.item(ContentRef("qobuz_backup", ContentKind.SONG, "abcdefghijk")) }
        } finally { client.close() }
    }
    @Test fun accountRedirectIsNotFollowedAndErrorsDoNotContainCredentialBodies() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                calls++
                respond("fixture-secret", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://other.invalid"))
            }
        } }
        try {
            val failure = assertFailsWith<ProviderHttpFailure> {
                KtorTidalRepository(client, TidalAccess("personal", "fixture-secret")).track("7")
            }
            assertEquals(302, failure.status)
            assertFalse(failure.toString().contains("fixture-secret"))
            assertEquals(1, calls)
            assertFalse(TidalAccess("personal", "fixture-secret").toString().contains("fixture-secret"))
            assertFalse(QobuzAccess("personal", "id", "fixture-secret", "fixture-secret").toString().contains("fixture-secret"))
            assertFalse(DeezerAccess("personal", "fixture-secret").toString().contains("fixture-secret"))
        } finally { client.close() }
    }
    @Test fun poolLeasesDefaultOffAndCannotUsePersonalCredentialsAsFallback() = runTest {
        val client = HttpClient(MockEngine { error("disabled Pool must not use any account endpoint") })
        try {
            val tidal = KtorTidalRepository(client, TidalAccess("pool:1", "fixture-token",
                credentialScope = CredentialScope.PLAYBACK_POOL))
            val qobuz = KtorQobuzRepository(client, { 0 }, QobuzAccess("pool:1", "fixture-app", "fixture-token", "fixture-secret",
                credentialScope = CredentialScope.PLAYBACK_POOL), QobuzRequestDigest { error("no signing") })
            val deezer = KtorDeezerRepository(client, { 0 }, DeezerAccess("pool:1", "fixture-arl",
                credentialScope = CredentialScope.PLAYBACK_POOL))
            for (repo in listOf(tidal, qobuz, deezer)) {
                assertFailsWith<UnsupportedContentOperation> { repo.resolve(request()) }
            }
        } finally { client.close() }
    }
    @Test fun qobuzAccountCannotHideMissingSignerBehindAnInstance() {
        val client = HttpClient(MockEngine { error("Invalid account configuration must not reach transport") })
        try {
            assertFailsWith<IllegalArgumentException> {
                KtorQobuzRepository(client, { 0 },
                    QobuzAccess("personal", "fixture-app", "fixture-token", "fixture-secret"),
                    instanceBase = "https://instance.invalid")
            }
        } finally { client.close() }
    }
    @Test fun providerExternalCancellationPropagatesAndOwnedTimeoutIsBounded() = runTest {
        var cancel = true
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { if (cancel) throw CancellationException("fixture") else { delay(13_000); respond("{}") } }
        } }
        try {
            val repo = KtorDeezerRepository(client, { 0 })
            assertFailsWith<CancellationException> { repo.track("7") }
            cancel = false
            assertFailsWith<ProviderRequestTimeout> { repo.track("7") }
        } finally { client.close() }
    }
}
