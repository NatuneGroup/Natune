package moe.rukamori.archivetune.shared.data.news

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.shared.domain.repository.ContentNews
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NatuneNewsClientTest {
    // The live public/api/news/index.json answer of the Natune site.
    private val liveIndex =
        """[{"id":"welcome-natune.md","Title":"Welcome to Natune","Description":"Our new home at natune.pages.dev is live — release notes, known issues and project news will land here first.","ImageURL":[],"Important":true,"Author":"Natune Team","Date":"1793625600"}]"""

    private val welcome = ContentNews(
        id = "welcome-natune.md",
        title = "Welcome to Natune",
        description = "Our new home at natune.pages.dev is live — release notes, known issues and project news will land here first.",
        imageUrls = emptyList(),
        important = true,
        author = "Natune Team",
        timestamp = 1_793_625_600L,
    )

    private class Reply(val body: String, val status: HttpStatusCode = HttpStatusCode.OK, val type: String = "application/json")

    private fun client(base: String, seen: MutableList<String>, reply: (String) -> Reply): NatuneNewsClient =
        NatuneNewsClient(
            HttpClient(
                MockEngine { request ->
                    val url = request.url.toString()
                    seen += url
                    val answer = reply(url)
                    respond(answer.body, answer.status, headersOf(HttpHeaders.ContentType, answer.type))
                },
            ),
            base,
        )

    @Test
    fun theLiveIndexParses() {
        assertEquals(listOf(welcome), parseNewsIndex(liveIndex))
    }

    @Test
    fun entriesSortNewestFirstAndToleratePortableShapes() {
        val text = """
            [
              {"id":"old.md","Title":"Old","Author":"A","Date":"100","ImageURL":"https://img/one.png","Extra":{"x":1}},
              {"id":"new.md","Title":"New","Author":"B","Date":300,"ImageURL":null,"Important":false},
              {"id":"untitled.md","Author":"C","Date":"999"},
              "not an entry",
              {"id":"mid.md","Title":"Mid","Author":"C","Date":"200","ImageURL":[" https://img/two.png ",""]}
            ]
        """.trimIndent()

        val items = parseNewsIndex(text)

        assertEquals(listOf("new.md", "mid.md", "old.md"), items.map { it.id })
        assertEquals(listOf(300L, 200L, 100L), items.map { it.timestamp })
        assertEquals(listOf("https://img/one.png"), items.last().imageUrls)
        assertEquals(listOf("https://img/two.png"), items[1].imageUrls)
        assertEquals(emptyList(), items.first().imageUrls)
    }

    @Test
    fun anIndexThatIsNotAJsonListFails() {
        assertFailsWith<NewsFeedException> { parseNewsIndex("""{"items":[]}""") }
        assertFailsWith<NewsFeedException> { parseNewsIndex("not json") }
    }

    @Test
    fun theFeedReadsIndexAndContentUnderTheBaseUrl() = runTest {
        val seen = mutableListOf<String>()
        val news = client(" https://news.example/api/news/ ", seen) { url ->
            if (url.endsWith("/index.json")) Reply(liveIndex) else Reply("# Welcome", type = "text/markdown")
        }

        assertTrue(news.enabled)
        assertEquals(listOf(welcome), news.list())
        assertEquals("# Welcome", news.content("welcome-natune.md"))
        assertEquals(welcome, news.cachedItem("welcome-natune.md"))
        assertEquals(
            listOf("https://news.example/api/news/index.json", "https://news.example/api/news/content/welcome-natune.md"),
            seen,
        )
    }

    @Test
    fun aBlankBaseUrlDisablesTheFeedWithoutARequest() = runTest {
        val seen = mutableListOf<String>()
        val news = client("  ", seen) { Reply(liveIndex) }

        assertFalse(news.enabled)
        assertEquals(emptyList(), news.list())
        assertEquals("", news.content("welcome-natune.md"))
        assertNull(news.cachedItem("welcome-natune.md"))
        assertEquals(emptyList(), seen)
    }

    @Test
    fun aFailedRefreshKeepsTheLastGoodIndex() = runTest {
        var healthy = true
        val news = client(DEFAULT_NATUNE_NEWS_URL, mutableListOf()) {
            if (healthy) Reply(liveIndex) else Reply("{}", HttpStatusCode.ServiceUnavailable)
        }

        assertEquals(listOf(welcome), news.list())
        healthy = false

        assertEquals(listOf(welcome), news.list())
        assertEquals(listOf(welcome), news.cached.value)
    }

    @Test
    fun aFailureBeforeAnyIndexLoadedSurfaces() = runTest {
        val news = client(DEFAULT_NATUNE_NEWS_URL, mutableListOf()) { Reply("{}", HttpStatusCode.NotFound) }

        val failure = assertFailsWith<NewsFeedException> { news.list() }

        assertTrue(failure.message.orEmpty().contains("HTTP 404"))
        assertNull(news.cached.value)
    }

    @Test
    fun anHtmlAnswerIsNeverTakenAsTheIndexOrAnArticle() = runTest {
        val shell = "<!doctype html><html><body>site</body></html>"
        val news = client(DEFAULT_NATUNE_NEWS_URL, mutableListOf()) { url ->
            when {
                url.endsWith("/index.json") -> Reply(liveIndex, type = "text/html; charset=utf-8")
                url.endsWith("/shell.md") -> Reply(shell, type = "text/plain")
                else -> Reply("# Body", type = "text/html")
            }
        }

        assertFailsWith<NewsFeedException> { news.list() }
        assertFailsWith<NewsFeedException> { news.content("shell.md") }
        assertFailsWith<NewsFeedException> { news.content("typed.md") }
        assertNull(news.cached.value)
    }

    @Test
    fun anUnsafeArticleIdIsRefusedWithoutARequest() = runTest {
        val seen = mutableListOf<String>()
        val news = client(DEFAULT_NATUNE_NEWS_URL, seen) { Reply("# Body", type = "text/markdown") }

        for (id in listOf("../secret", "a/b.md", "..", "", "id?x=1")) {
            assertFailsWith<NewsFeedException> { news.content(id) }
        }
        assertEquals(emptyList(), seen)
    }

    @Test
    fun responseGuardMatchesTheAndroidReasons() {
        assertEquals("HTTP 404", invalidNewsResponse(404, "application/json", "{}"))
        assertEquals("empty body", invalidNewsResponse(200, "application/json", "   "))
        assertEquals("empty body", invalidNewsResponse(200, "application/json", null))
        assertEquals("HTML content-type", invalidNewsResponse(200, "text/html; charset=utf-8", "# Title"))
        assertEquals("HTML shell, not article content", invalidNewsResponse(200, "text/plain", "  <html><body/></html>"))
        assertEquals("HTML shell, not article content", invalidNewsResponse(200, null, "<!DOCTYPE html>"))
        assertNull(invalidNewsResponse(200, "text/markdown", "<p>embedded</p>"))
        assertNull(invalidNewsResponse(200, null, "# markdown without content-type"))
    }

    @Test
    fun unreadMeansAnItemNewerThanTheLastRead() {
        assertFalse(hasUnreadNews(emptyList(), 0L))
        assertTrue(hasUnreadNews(listOf(welcome), 0L))
        assertTrue(hasUnreadNews(listOf(welcome), welcome.timestamp - 1))
        assertFalse(hasUnreadNews(listOf(welcome), welcome.timestamp))
    }

    @Test
    fun closingReleasesTheFeedClientAndLeavesTheBorrowedEngineUsable() = runTest {
        val engine = MockEngine { respond(liveIndex, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val shared = HttpClient(engine)
        val news = NatuneNewsClient(shared, DEFAULT_NATUNE_NEWS_URL)
        assertEquals(listOf(welcome), news.list())
        assertTrue(news.isOpen)

        news.close()

        assertFalse(news.isOpen)
        assertTrue(engine.isActive)
        assertEquals(liveIndex, shared.get("https://elsewhere.example/index.json").bodyAsText())
        shared.close()
    }

    @Test
    fun closingTheBorrowedClientAlsoReleasesTheFeedSoTheEngineCanFinish() = runTest {
        val engine = MockEngine { respond(liveIndex, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val shared = HttpClient(engine)
        val news = NatuneNewsClient(shared, DEFAULT_NATUNE_NEWS_URL)
        assertEquals(listOf(welcome), news.list())

        shared.close()

        // The engine's job completes only once every client job under it has, the feed's private one included.
        withContext(Dispatchers.Default) { withTimeout(5_000) { engine.coroutineContext[Job]!!.join() } }
        assertFalse(news.isOpen)
    }
}
