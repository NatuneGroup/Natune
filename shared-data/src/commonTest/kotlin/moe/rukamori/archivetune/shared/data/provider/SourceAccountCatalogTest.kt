package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlin.test.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.settings.QobuzTokenEntry

class SourceAccountCatalogTest {
    @Test fun qobuzAdmissionRequiresTheRealVerifiedUserNotATokenOrFeedId() = runTest {
        var response = """{"user":{"id":42}}"""
        // Run the handler on the test scheduler: a Default-dispatcher handler lets runTest's virtual clock reach the 12 s provider timeout first.
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                assertEquals("/api.json/0.2/user/get", request.url.encodedPath)
                assertEquals("fixture-user", request.headers["X-User-Auth-Token"])
                assertEquals("fixture-app", request.url.parameters["app_id"])
                respond(response)
            }
        } }
        try {
            val identity = KtorQobuzAccountIdentity(client)
            val entry = QobuzTokenEntry("fixture-user", appId = "fixture-app", appSecret = "fixture-secret", poolId = 900)
            assertEquals("42", identity.userId(entry))
            assertNull(identity.userId(entry.copy(userId = "900")))
            response = """{"data":{"id":0}}"""
            assertNull(identity.userId(entry))
            response = """{"data":{"name":"No user id"}}"""
            assertNull(identity.userId(entry))
        } finally { client.close() }
    }

    @Test fun amazonSuggestionsKeepMetadataAndAreAnonymousNotAmazonCatalogIds() = runTest {
        var requests = 0
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { request ->
                requests++
                assertEquals("itunes.apple.com", request.url.host)
                assertEquals("song", request.url.parameters["entity"])
                assertEquals("Song", request.url.parameters["term"])
                assertNull(request.headers["Authorization"])
                assertNull(request.headers["Cookie"])
                respond("""{"results":[{"trackId":7,"trackName":"Song","artistName":"Artist","collectionName":"Album","trackTimeMillis":180000},{"trackId":7,"trackName":"Song","artistName":"Artist"},{"trackId":8,"trackName":"Missing artist"}]}""")
            }
        } }
        try {
            val search = KtorAmazonSourceSuggestions(client)
            assertTrue(search.search("x").isEmpty())
            val row = search.search(" Song ").single()
            assertEquals(1, requests)
            assertEquals(ContentProvider.AMAZON.id, row.ref.provider)
            assertEquals("Artist", row.artists.single().name)
            assertNull(row.artists.single().id)
            assertEquals("Album", row.album)
            assertEquals(180_000L, row.durationMs)
        } finally { client.close() }
    }
}
