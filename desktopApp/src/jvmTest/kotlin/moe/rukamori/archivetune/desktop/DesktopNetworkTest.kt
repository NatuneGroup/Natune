package moe.rukamori.archivetune.desktop

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ProxyEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyHostKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyPasswordKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyPortKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyTypeKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyUsernameKey
import moe.rukamori.archivetune.shared.domain.settings.StreamBypassProxyKey
import okhttp3.Credentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopNetworkTest {
    private val remote = URI("https://music.youtube.com")

    @Test fun savedProxyUsesTheSharedHostGrammarPortCredentialsAndStreamPolicy() {
        val config = assertNotNull(desktopProxyConfig(PreferenceSnapshot.of(
            ProxyEnabledKey to true,
            ProxyHostKey to "socks5://ignored@proxy.example:1080/",
            ProxyPortKey to 8080,
            ProxyTypeKey to "SOCKS",
            ProxyUsernameKey to " user ",
            ProxyPasswordKey to " fixture password ",
            StreamBypassProxyKey to true,
        )))
        assertEquals(Proxy.Type.SOCKS, config.proxy.type())
        assertEquals(InetSocketAddress.createUnresolved("proxy.example", 1080), config.proxy.address())
        assertEquals("user", config.username)
        assertEquals(" fixture password ", config.password)
        assertTrue(config.bypassStreams)
        assertTrue(config.hasCredentials)
        assertFalse(config.toString().contains("password"))
    }

    @Test fun disabledMalformedAndOutOfRangeProxySettingsDoNotInstallAProxy() {
        assertNull(desktopProxyConfig(PreferenceSnapshot.EMPTY))
        assertNull(desktopProxyConfig(PreferenceSnapshot.of(ProxyEnabledKey to true, ProxyHostKey to "")))
        assertNull(desktopProxyConfig(PreferenceSnapshot.of(
            ProxyEnabledKey to true, ProxyHostKey to "proxy.example", ProxyPortKey to 0,
        )))
    }

    @Test fun staticProxyEditsAndDisableAreVisibleThroughTheSameSelector() {
        val systemProxy = proxy("system.example")
        var configured: DesktopProxyConfig? = config(proxy("first.example"))
        val selector = DesktopProxySelector({ configured }, { null }, fixed(systemProxy))
        assertEquals(configured?.proxy, selector.select(remote).single())
        configured = config(proxy("second.example"))
        assertEquals(configured?.proxy, selector.select(remote).single())
        configured = null
        assertEquals(systemProxy, selector.select(remote).single())
    }

    @Test fun rotationTakesPrecedenceAndStreamBypassKeepsTheSystemRoute() {
        val rotating = proxy("rotation.example")
        val system = proxy("system.example")
        val selector = DesktopProxySelector(
            { config(proxy("static.example"), bypass = true) }, { fixed(rotating) }, fixed(system),
        )
        assertEquals(rotating, selector.select(remote).single())
        assertEquals(system, selector.selectForStream(remote).single())
        for (host in listOf("localhost", "speaker.local", "127.0.0.1", "192.168.0.1", "[::1]", "[fd00::1]")) {
            assertEquals(Proxy.NO_PROXY, selector.select(URI("http://$host/")).single())
        }
    }

    @Test fun poolRotatesPerSelectionAndFailureMarksTheActualAddressNotTheLastSelection() {
        val selector = DesktopRotatingProxySelector()
        selector.loadProxies(listOf("first.example" to 8080, "second.example" to 8080))
        val first = selector.select(remote).single()
        val second = selector.select(remote).single()
        assertFalse(first == second)
        selector.connectFailed(remote, first.address(), IOException())
        assertEquals(1, selector.activeCount())
        assertEquals(second, selector.select(remote).single())
        selector.connectFailed(remote, second.address(), IOException())
        assertEquals(0, selector.activeCount())
        assertFalse(selector.select(remote).single() == Proxy.NO_PROXY)
    }

    @Test fun startupInstallsSavedPolicyForTheProcessAndInnerTubeWithoutOpeningSettings() = runBlocking {
        val previous = ProxySelector.getDefault()
        val preferences = PreferenceSnapshot.of(
            ProxyEnabledKey to true, ProxyHostKey to "proxy.example", ProxyPortKey to 8080,
            StreamBypassProxyKey to true,
        )
        val network = DesktopNetworkOwner { preferences }
        try {
            network.applyNetworkSettings(preferences)
            assertSame(network.selector, ProxySelector.getDefault())
            assertEquals(desktopProxyConfig(preferences)?.proxy, YouTube.proxy)
            assertTrue(YouTube.streamBypassProxy)
        } finally {
            network.close()
        }
        assertSame(previous, ProxySelector.getDefault())
    }

    @Test fun theGraphHttpTransportReadsTheSelectorAgainAfterAnEdit() = runBlocking {
        ProxyServer { it.answer(200, "first") }.use { first ->
            ProxyServer { it.answer(200, "second") }.use { second ->
                var preferences = first.preferences()
                val network = DesktopNetworkOwner { preferences }
                network.applyNetworkSettings(preferences)
                val client = network.httpClient()
                try {
                    assertEquals("first", client.get("http://catalogue.invalid/").bodyAsText())
                    preferences = second.preferences()
                    network.applyNetworkSettings(preferences)
                    assertEquals("second", client.get("http://catalogue.invalid/").bodyAsText())
                } finally {
                    client.close()
                    network.close()
                }
            }
        }
    }

    @Test fun httpProxyCredentialsAnswerOnlyTheConfiguredProxyAndDoNotLoopOnRejection() = runBlocking {
        val calls = AtomicInteger()
        val credential = AtomicReference<String?>()
        ProxyServer { exchange ->
            calls.incrementAndGet()
            credential.set(exchange.requestHeaders.getFirst("Proxy-Authorization"))
            exchange.responseHeaders.add("Proxy-Authenticate", "Basic realm=\"fixture\"")
            exchange.answer(407, "")
        }.use { proxy ->
            val configured = DesktopProxyConfig(proxy.proxy, "user", "fixture password", false)
            val client = desktopProxyHttpClient(fixed(proxy.proxy)) { configured }
            try {
                assertEquals(407, client.get("http://catalogue.invalid/").status.value)
                assertEquals(2, calls.get())
                assertEquals(Credentials.basic("user", "fixture password", Charsets.UTF_8), credential.get())
            } finally {
                client.close()
            }
            calls.set(0)
            credential.set(null)
            val unrelated = desktopProxyHttpClient(fixed(proxy.proxy)) { config(this@DesktopNetworkTest.proxy("other.example")) }
            try {
                assertEquals(407, unrelated.get("http://catalogue.invalid/").status.value)
                assertEquals(1, calls.get())
                assertNull(credential.get())
            } finally {
                unrelated.close()
            }
        }
    }

    private fun proxy(host: String): Proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, 8080))
    private fun config(proxy: Proxy, bypass: Boolean = false) = DesktopProxyConfig(proxy, null, null, bypass)
    private fun fixed(proxy: Proxy) = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }

    private class ProxyServer(handler: (HttpExchange) -> Unit) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange -> exchange.use(handler) }
            start()
        }
        val proxy = Proxy(Proxy.Type.HTTP, server.address)
        fun preferences() = PreferenceSnapshot.of(
            ProxyEnabledKey to true, ProxyHostKey to "127.0.0.1", ProxyPortKey to server.address.port,
        )
        override fun close() = server.stop(0)
    }
}

private fun HttpExchange.answer(status: Int, value: String) {
    val body = value.toByteArray()
    sendResponseHeaders(status, if (body.isEmpty()) -1L else body.size.toLong())
    if (body.isNotEmpty()) responseBody.write(body)
}
