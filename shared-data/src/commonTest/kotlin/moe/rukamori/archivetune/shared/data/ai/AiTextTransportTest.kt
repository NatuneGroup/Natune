package moe.rukamori.archivetune.shared.data.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.lyrics.ai.*

@OptIn(ExperimentalCoroutinesApi::class)
class AiTextTransportTest {
    private val clients = mutableListOf<HttpClient>()
    @AfterTest fun closeClients() { clients.forEach { it.close() } }
    private fun config(provider: AiProvider = AiProvider.CHATGPT) =
        AiServiceSettings(provider, "fixture-credential", "", "")
    private fun response(content: String) = buildJsonObject {
        putJsonArray("choices") { addJsonObject { putJsonObject("message") { put("content", content) } } }
    }.toString()
    private fun TestScope.service(handler: suspend (io.ktor.client.request.HttpRequestData) -> String) =
        KtorAiTextService(HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { req -> respond(handler(req), headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            }
        }.also { clients += it }, AiRateLimiter({ testScheduler.currentTime }, { delay(it) }))

    @Test fun openAiUsesArrayAndPreservesUnicode() = runTest {
        val service = service {
            assertEquals("https://api.openai.com/v1/chat/completions", it.url.toString())
            assertEquals("Bearer fixture-credential", it.headers[HttpHeaders.Authorization])
            response("[\"مرحبا 👩‍👩‍👦\", \"हंस\"]")
        }
        assertEquals(listOf("مرحبا 👩‍👩‍👦", "हंस"),
            service.translateLines(config(), "Arabic", listOf("one", "two"), "TTML"))
    }
    @Test fun rejectsPartialAndNonStringSegments() = runTest {
        for (bad in listOf("[\"one\"]", "[\"one\", null]", "[\"one\", \"\"]", "[1,2]")) {
            assertFailsWith<AiTransportException> {
                service { response(bad) }.translateLines(config(), "English", listOf("a", "b"), "LRC")
            }
        }
    }
    @Test fun deepLUsesTargetMappingAndFreeTier() = runTest {
        val result = service {
            assertEquals("api-free.deepl.com", it.url.host)
            assertEquals("DeepL-Auth-Key fixture:fx", it.headers[HttpHeaders.Authorization])
            """{"translations":[{"text":"bonjour"}]}"""
        }.translateLines(config(AiProvider.DEEPL).copy(apiKey = "fixture:fx"), "French", listOf("hello"), "plain")
        assertEquals(listOf("bonjour"), result)
    }
    @Test fun deepLNeverRomanizes() = runTest {
        assertFailsWith<AiTransportException> {
            service { error("must not call") }.romanizeLines(config(AiProvider.DEEPL), listOf("字"), "plain")
        }
    }
    @Test fun geminiUsesHeaderAndFiltersModels() = runTest {
        val models = service {
            assertEquals("fixture-credential", it.headers["x-goog-api-key"])
            assertNull(it.url.parameters["key"])
            """{"models":[{"name":"models/one","supportedGenerationMethods":["generateContent"]},
                {"name":"models/embed","supportedGenerationMethods":["embedContent"]}]}"""
        }.listModels(config(AiProvider.GEMINI))
        assertEquals(listOf("one"), models)
    }
    @Test fun geminiGenerationUsesNativeWireFormatAndValidatesSegments() = runTest {
        val result = service {
            assertTrue(it.url.encodedPath.endsWith("/gemini-3.5-flash:generateContent"))
            val body = Json.parseToJsonElement((it.body as TextContent).text).jsonObject
            assertTrue("systemInstruction" in body && "contents" in body && "generationConfig" in body)
            """{"candidates":[{"content":{"parts":[{"text":"[\"hola\"]"}]}}]}"""
        }.translateLines(config(AiProvider.GEMINI), "Spanish", listOf("hello"), "TTML")
        assertEquals(listOf("hola"), result)
    }
    @Test fun mistralRomanizationNeverRequestsTranslationAndKeepsLineCount() = runTest {
        val result = service {
            assertEquals("api.mistral.ai", it.url.host)
            val body = Json.parseToJsonElement((it.body as TextContent).text).jsonObject
            val messages = body.getValue("messages").jsonArray
            assertTrue(messages[0].jsonObject.getValue("content").jsonPrimitive.content.contains("Do not translate"))
            assertTrue(messages[1].jsonObject.getValue("content").jsonPrimitive.content.startsWith("Romanize"))
            response("hoshi\nsora")
        }.romanizeLines(config(AiProvider.MISTRAL), listOf("星", "空"), "LRC")
        assertEquals(listOf("hoshi", "sora"), result)
    }
    @Test fun oversizedResponsesAreRejectedRatherThanCachedAsPartialLyrics() = runTest {
        assertFailsWith<IllegalArgumentException> {
            service { " ".repeat(2_000_001) }.translateLines(config(), "English", listOf("hello"), "LRC")
        }
    }
    @Test fun openRouterCustomBaseAndModelSorting() = runTest {
        val models = service {
            assertEquals("https://router.example/v1/models", it.url.toString())
            """{"data":[{"id":"z"},{"id":"a"}]}"""
        }.listModels(config(AiProvider.OPENROUTER).copy(customEndpoint = "https://router.example"))
        assertEquals(listOf("a", "z"), models)
    }
    @Test fun customEndpointAndModelUsedPerCall() = runTest {
        val urls = mutableListOf<String>()
        val transport = service { urls += it.url.toString(); response("[\"x\"]") }
        transport.translateLines(config(AiProvider.CUSTOM).copy(customEndpoint = "https://first.example/chat", model = "a"),
            "English", listOf("a"), "plain")
        transport.translateLines(config(AiProvider.CUSTOM).copy(customEndpoint = "http://127.0.0.1:8080/chat", model = "b"),
            "English", listOf("a"), "plain")
        assertEquals(listOf("https://first.example/chat", "http://127.0.0.1:8080/chat"), urls)
    }
    @Test fun cancellationPropagatesWithoutFailureConversion() = runTest {
        assertFailsWith<CancellationException> {
            service { throw CancellationException("cancel fixture") }
                .translateLines(config(), "English", listOf("a"), "plain")
        }
    }
    @Test fun batchesKeepOrderAndDoNotReturnPartialSuccess() = runTest {
        var calls = 0
        val limiter = AiRateLimiter(nowMs = { testScheduler.currentTime }, wait = { delay(it) })
        val transport = KtorAiTextService(HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler {
                    calls++
                    respond(response(if(calls == 1) "[\"A\", \"B\"]" else "[\"C\"]"),
                        headers = headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        }.also { clients += it }, limiter, batchSize = 2)
        assertEquals(listOf("A", "B", "C"),
            transport.translateLines(config(), "English", listOf("a", "b", "c"), "LRC"))
        assertEquals(2, calls)
        assertEquals(1_000L, testScheduler.currentTime)
    }
    @Test fun apiCheckDoesNotAssumeAnyResponseIsHealthy() = runTest {
        service { response("OK") }.test(config())
        assertFailsWith<AiTransportException> { service { response("not healthy") }.test(config()) }
    }
    @Test fun ownedTimeoutIsFailureWhileExternalCancellationRemainsCancellation() = runTest {
        assertFailsWith<AiTransportException> {
            service { delay(61_000); response("[\"late\"]") }
                .translateLines(config(), "English", listOf("a"), "LRC")
        }
    }
}
