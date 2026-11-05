package moe.rukamori.archivetune.shared.data.provider

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The cooldown book is read under the health mutex; the resulting endpoint order is observed here. */
class TidalInstanceCooldownTest {
    private val searchReply =
        """{"items":[{"id":"42","title":"Hello","artists":[{"name":"Adele"}],"album":{"title":"25"}}]}"""

    private fun resolver(hosts: MutableList<String>): TidalInstanceResolver {
        val engine =
            MockEngine { request ->
                hosts += request.url.host
                respond(searchReply, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        return TidalInstanceResolver(HttpClient(engine), nowMs = { NOW_MS })
    }

    @Test fun aHardFailedInstanceIsTriedAfterTheHealthyOnes() = runTest {
        val instances = listOf("https://b.example", "https://a.example")

        val hosts = mutableListOf<String>()
        val fresh = resolver(hosts)
        fresh.setInstances(instances)
        assertEquals("42", fresh.findHealthProbeTrackId())
        assertEquals(listOf("b.example"), hosts)

        hosts.clear()
        val cooled = resolver(hosts)
        cooled.setInstances(instances)
        cooled.applyHealthResult("https://b.example", healthy = false)
        assertEquals("42", cooled.findHealthProbeTrackId())
        assertEquals(listOf("a.example"), hosts)

        hosts.clear()
        val recovered = resolver(hosts)
        recovered.setInstances(instances)
        recovered.applyHealthResult("https://b.example", healthy = false)
        recovered.applyHealthResult("https://b.example", healthy = true)
        assertEquals("42", recovered.findHealthProbeTrackId())
        assertEquals(listOf("b.example"), hosts)
    }

    private companion object {
        const val NOW_MS = 1_000_000L
    }
}
