package moe.rukamori.archivetune.desktop.potoken

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class BgutilPotTokenClientTest {
    @Test
    fun mintsThePlayerAndSessionBindings() = runTest {
        val transport = FakeTransport()
        val client = BgutilPotTokenClient(transport, nowMs = { 1_000L })

        val minted = client.mint(BASE, videoId = "video-1", sessionId = "visitor-1")

        assertEquals("token-video-1", minted?.playerToken)
        assertEquals("token-visitor-1", minted?.sessionToken)
        assertEquals(listOf("video-1", "visitor-1"), transport.requests)
    }

    @Test
    fun cachesEachBindingUntilItsExpiry() = runTest {
        val transport = FakeTransport()
        var now = 1_000L
        val client = BgutilPotTokenClient(transport, nowMs = { now }, ttlMs = 100L)

        assertEquals("token-video-1", client.token(BASE, "video-1"))
        assertEquals("token-video-1", client.token(BASE, "video-1"))
        assertEquals(listOf("video-1"), transport.requests)

        now += 101L
        assertEquals("token-video-1", client.token(BASE, "video-1"))
        assertEquals(listOf("video-1", "video-1"), transport.requests)
    }

    @Test
    fun aMissingBindingMakesThePairNullInsteadOfHalfAToken() = runTest {
        val transport = FakeTransport(failFor = "visitor-1")
        val client = BgutilPotTokenClient(transport, nowMs = { 1_000L })

        assertNull(client.mint(BASE, videoId = "video-1", sessionId = "visitor-1"))
    }

    @Test
    fun clearDropsEveryCachedBinding() = runTest {
        val transport = FakeTransport()
        val client = BgutilPotTokenClient(transport, nowMs = { 1_000L })
        client.token(BASE, "video-1")

        client.clear()
        client.token(BASE, "video-1")

        assertEquals(listOf("video-1", "video-1"), transport.requests)
    }

    @Test
    fun blankBindingsAreNeverRequested() = runTest {
        val transport = FakeTransport()
        val client = BgutilPotTokenClient(transport, nowMs = { 1_000L })

        assertNull(client.token(BASE, "   "))
        assertNull(client.mint(BASE, videoId = "", sessionId = "visitor-1"))
        assertEquals(emptyList<String>(), transport.requests)
    }

    private class FakeTransport(private val failFor: String? = null) : BgutilSidecarTransport {
        val requests = mutableListOf<String>()

        override suspend fun ping(baseUrl: String): Boolean = true

        override suspend fun fetchPot(baseUrl: String, contentBinding: String): String? {
            requests += contentBinding
            return if (contentBinding == failFor) null else "token-$contentBinding"
        }
    }

    private companion object {
        const val BASE = "http://127.0.0.1:4416"
    }
}
