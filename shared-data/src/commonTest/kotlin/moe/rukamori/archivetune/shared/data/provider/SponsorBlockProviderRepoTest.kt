package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlin.test.*

class SponsorBlockProviderRepoTest {
    @Test fun usesHashPrefixFiltersActionsAndMergesOverlap() = runTest {
        val id = "abcdefghijk"
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler {
                assertFalse(it.url.toString().contains(id))
                assertEquals(listOf("music_offtopic"), it.url.parameters.getAll("category"))
                respond("""[{"videoID":"abcdefghijk","segments":[
                {"category":"music_offtopic","actionType":"skip","segment":[1,3]},
                {"category":"music_offtopic","actionType":"skip","segment":[2,5]},
                {"category":"sponsor","actionType":"skip","segment":[6,7]},
                {"category":"music_offtopic","actionType":"mute","segment":[8,9]}]}]""")
            }
        } }
        try {
            assertEquals(listOf(SkipSegment("music_offtopic", 1000, 5000)),
                KtorSponsorBlockRepository(client).segments(id, SponsorBlockConfig(true)))
        } finally { client.close() }
    }
    @Test fun offAndInvalidIdsMakeNoRequestsAndCancellationEscapes() = runTest {
        val client = HttpClient(MockEngine { error("must not call") })
        try {
            val repo = KtorSponsorBlockRepository(client)
            assertTrue(repo.segments("local-file", SponsorBlockConfig(true)).isEmpty())
            assertTrue(repo.segments("abcdefghijk", SponsorBlockConfig(false)).isEmpty())
        } finally { client.close() }
        val cancelled = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { throw CancellationException("fixture") }
        } }
        try {
            assertFailsWith<CancellationException> {
                KtorSponsorBlockRepository(cancelled).segments("abcdefghijk", SponsorBlockConfig(true))
            }
        } finally { cancelled.close() }
    }
    @Test fun ownTimeoutIsOnlyAnEnhancementFailure() = runTest {
        val client = HttpClient(MockEngine) { engine {
            dispatcher = StandardTestDispatcher(testScheduler)
            addHandler { delay(13_000); respond("[]") }
        } }
        try {
            assertTrue(KtorSponsorBlockRepository(client).segments("abcdefghijk", SponsorBlockConfig(true)).isEmpty())
        } finally { client.close() }
    }
    @Test fun originValidationRejectsMissingAuthoritiesCredentialsAndUnexpectedPaths() {
        for (url in listOf(
            "http://example.com", "https:", "https:/", "https://", "https:///", "https:///example.com",
            "https://:443", "/", "//example.com", "https://user:pass@example.com", "https://@example.com",
            "https://example.com/path", "https://example.com//", "https://example.com?q=x", "https://example.com#fragment",
        )) {
            assertFailsWith<IllegalArgumentException>(url) { SponsorBlockConfig(true, origin = url) }
        }
    }

    @Test fun validHttpsOriginsKeepCustomHostsPortsAndIpv6() {
        for (origin in listOf(
            "https://segments.example.test", "https://segments.example.test/",
            "https://segments.example.test:8443", "https://[2001:db8::1]:8443",
        )) {
            assertEquals(origin, SponsorBlockConfig(true, origin = origin).origin)
        }
    }
}
