package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.*

class YouTubeUploadClientTest {
    private val session = YouTubeUploadSession(
        cookie = "VISITOR_INFO1_LIVE=v; SAPISID=sapisid-fixture; other=1",
        visitorData = "visitor-fixture",
        dataSyncId = "delegate-fixture||",
    )

    private class Content(override val length: Long) : YouTubeUploadContent {
        var reads = 0

        override suspend fun read(buffer: ByteArray): Int {
            reads++
            return -1
        }
    }

    @Test fun validationAndSignInRunBeforeAnyRequest() = runTest {
        val requests = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine { addHandler { requests += it.url.toString(); respond(content = "") } } }
        try {
            val content = Content(10)
            val uploads = YouTubeUploadClient(client, { session })
            assertEquals(YouTubeUploadResult.Failed(YouTubeUploadFailure.UNSUPPORTED_TYPE), uploads.upload("notes.txt", content))
            assertEquals(YouTubeUploadResult.Failed(YouTubeUploadFailure.EMPTY_FILE), uploads.upload("song.mp3", Content(0)))
            assertEquals(
                YouTubeUploadResult.Failed(YouTubeUploadFailure.TOO_LARGE),
                uploads.upload("song.mp3", Content(YouTubeUploadPolicy.MAX_BYTES)),
            )
            assertEquals(
                YouTubeUploadResult.Failed(YouTubeUploadFailure.SIGN_IN_REQUIRED),
                YouTubeUploadClient(client, { null }).upload("song.mp3", Content(10)),
            )
            assertEquals(
                YouTubeUploadResult.Failed(YouTubeUploadFailure.SIGN_IN_REQUIRED),
                YouTubeUploadClient(client, { YouTubeUploadSession("VISITOR=1", null, null) }).upload("song.mp3", Content(10)),
            )
            assertTrue(requests.isEmpty(), requests.toString())
            assertEquals(0, content.reads)
        } finally {
            client.close()
        }
    }

    @Test fun conflictOnStartIsInformationalNotAFailure() = runTest {
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { respond(content = "", status = HttpStatusCode.Conflict) }
        } }
        try {
            assertEquals(
                YouTubeUploadResult.AlreadyUploaded,
                YouTubeUploadClient(client, { session }).upload("song.flac", Content(10)),
            )
        } finally {
            client.close()
        }
    }

    @Test fun finalizeUsesTheStartUploadIdAndReportsTheServerStatus() = runTest {
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                urls += request.url.toString()
                if (request.headers["X-Goog-Upload-Command"] == "start") {
                    respond(content = "", status = HttpStatusCode.OK, headers = headersOf("X-Goog-Upload-Id", "upload-fixture"))
                } else {
                    respond(content = "", status = HttpStatusCode.InternalServerError)
                }
            }
        } }
        try {
            assertEquals(
                YouTubeUploadResult.Failed(YouTubeUploadFailure.HTTP, 500),
                YouTubeUploadClient(client, { session }).upload("song.mp3", Content(10)),
            )
            assertEquals(2, urls.size, urls.toString())
            assertTrue(urls[1].contains("upload_id=upload-fixture"), urls[1])
            assertTrue(urls.all { it.startsWith("https://upload.youtube.com/upload/usermusic/http") }, urls.toString())
        } finally {
            client.close()
        }
    }

    @Test fun foreignSessionUrlIsIgnoredAndUploadFailsClosedWithoutAnUploadId() = runTest {
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                urls += request.url.toString()
                if (request.headers["X-Goog-Upload-Command"] == "start") {
                    respond(
                        content = "",
                        status = HttpStatusCode.OK,
                        headers = headersOf("X-Goog-Upload-Url", "https://attacker.example/upload"),
                    )
                } else {
                    respond(content = "", status = HttpStatusCode.OK)
                }
            }
        } }
        try {
            // The foreign session URL is ignored and no upload id was given, so the upload fails closed.
            assertEquals(
                YouTubeUploadResult.Failed(YouTubeUploadFailure.UNKNOWN, 200),
                YouTubeUploadClient(client, { session }).upload("song.mp3", Content(10)),
            )
            assertTrue(urls.none { it.contains("attacker.example") }, urls.toString())
            assertEquals(1, urls.size, urls.toString())
        } finally {
            client.close()
        }
    }

    @Test fun deleteReportsEveryFailureAndOnlyAcceptsACleanSuccess() = runTest {
        var status = HttpStatusCode.InternalServerError
        var body = ""
        val urls = mutableListOf<String>()
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                urls += request.url.toString()
                respond(content = body, status = status)
            }
        } }
        try {
            val uploads = YouTubeUploadClient(client, { session })
            assertEquals(YouTubeDeleteResult.Failed(YouTubeUploadFailure.HTTP, 500), uploads.delete("entity-1"))
            status = HttpStatusCode.OK
            body = """{"error":{"code":400,"message":"rejected"}}"""
            assertEquals(YouTubeDeleteResult.Failed(YouTubeUploadFailure.UNKNOWN, 200), uploads.delete("entity-1"))
            body = "[1,2]"
            assertEquals(YouTubeDeleteResult.Failed(YouTubeUploadFailure.UNKNOWN, 200), uploads.delete("entity-1"))
            body = "{}"
            assertEquals(YouTubeDeleteResult.Deleted, uploads.delete("entity-1"))
            assertTrue(urls.last().endsWith("music/delete_privately_owned_entity?prettyPrint=false"), urls.last())
            assertEquals(YouTubeDeleteResult.Failed(YouTubeUploadFailure.UNKNOWN), uploads.delete(""))
            assertEquals(4, urls.size, urls.toString())
        } finally {
            client.close()
        }
    }

    @Test fun signedOutDeleteAndBrowseNeverSendRequests() = runTest {
        val client = HttpClient(MockEngine) { engine { addHandler { error("no request expected") } } }
        try {
            val signedOut = YouTubeUploadClient(client, { null })
            assertEquals(YouTubeDeleteResult.Failed(YouTubeUploadFailure.SIGN_IN_REQUIRED), signedOut.delete("entity-1"))
            val failure = assertFailsWith<YouTubeUploadException> { signedOut.browseUploads() }
            assertEquals(YouTubeUploadFailure.SIGN_IN_REQUIRED, failure.failure)
        } finally {
            client.close()
        }
    }

    @Test fun browseParsesRowsDeleteIdsAndContinuation() {
        val page = YouTubeUploadBrowse.parse(Json.parseToJsonElement(browsePage))
        assertEquals(
            listOf(
                YouTubeUploadedSong("abcdefghijk", "entity-1", "Track one", "Artist", "Album", 225, "https://i.example/large.jpg"),
                YouTubeUploadedSong("klmnopqrstu", null, "Track two", null, null, null, null),
            ),
            page.songs,
        )
        assertEquals("next-token", page.continuation)
    }

    @Test fun policyAcceptsTheDocumentedFormatsAndSizes() {
        assertNull(YouTubeUploadPolicy.validate("Song.MP3", 1))
        assertEquals(YouTubeUploadFailure.UNSUPPORTED_TYPE, YouTubeUploadPolicy.validate("song.aac", 1))
        assertEquals(YouTubeUploadFailure.UNSUPPORTED_TYPE, YouTubeUploadPolicy.validate("song", 1))
        assertEquals(YouTubeUploadFailure.EMPTY_FILE, YouTubeUploadPolicy.validate("song.ogg", 0))
        assertNull(YouTubeUploadPolicy.validate("song.wma", YouTubeUploadPolicy.MAX_BYTES - 1))
        assertEquals(YouTubeUploadFailure.TOO_LARGE, YouTubeUploadPolicy.validate("song.wma", YouTubeUploadPolicy.MAX_BYTES))
    }

    @Test fun loginCookieAndDelegateFollowTheCoreRules() {
        assertEquals("first", YouTubeUploadAuth.loginCookieValue("__Secure-3PAPISID=third; SAPISID=first"))
        assertEquals("second", YouTubeUploadAuth.loginCookieValue("__Secure-1PAPISID=x; __Secure-3PAPISID=second"))
        assertNull(YouTubeUploadAuth.loginCookieValue("SAPISID=; other=1"))
        assertEquals("abc", YouTubeUploadSession("", null, "abc||def").delegatedSessionId())
        assertNull(YouTubeUploadSession("", null, "abc||").delegatedSessionId())
        assertNull(YouTubeUploadSession("", null, "||abc").delegatedSessionId())
    }

    private companion object {
        val browsePage = """
            {"contents":{"singleColumnBrowseResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[{"musicShelfRenderer":{
              "contents":[
                {"musicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"abcdefghijk"},
                  "flexColumns":[
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track one"}]}}},
                    {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist"},{"text":" • "},{"text":"Album"},{"text":" • "},{"text":"3:45"}]}}}
                  ],
                  "fixedColumns":[{"musicResponsiveListItemFixedColumnRenderer":{"text":{"runs":[{"text":"3:45"}]}}}],
                  "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{"url":"https://i.example/small.jpg"},{"url":"https://i.example/large.jpg"}]}}},
                  "menu":{"menuRenderer":{"items":[{"menuServiceItemRenderer":{"serviceEndpoint":{"musicDeletePrivatelyOwnedEntityCommand":{"entityId":"entity-1"}}}}]}}
                }},
                {"musicResponsiveListItemRenderer":{
                  "playlistItemData":{"videoId":"klmnopqrstu"},
                  "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Track two"}]}}}]
                }}
              ],
              "continuations":[{"nextContinuationData":{"continuation":"next-token"}}]
            }}]}}}}]}}}
        """.trimIndent()
    }
}
