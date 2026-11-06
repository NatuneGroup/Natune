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

    @Test fun rotationActivatesOnlyAfterBothClientPoolsHaveLiveRoutes() {
        assertFalse(desktopRotationReady(0, 0))
        assertFalse(desktopRotationReady(0, 3))
        assertFalse(desktopRotationReady(3, 0))
        assertTrue(desktopRotationReady(3, 5))
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

    /**
     * A resolved googlevideo URL is only reusable for the address that minted it. The direct route and a saved static
     * proxy therefore have to name themselves, and a proxy scope may never carry the credentials that reach it.
     */
    @Test fun theDirectRouteAndASavedProxyEachNameThemselvesWithoutCredentials() = runBlocking<Unit> {
        val direct = DesktopNetworkOwner { PreferenceSnapshot.EMPTY }
        try {
            direct.applyNetworkSettings(PreferenceSnapshot.EMPTY)
            assertEquals(DIRECT_ROUTE_SCOPE, direct.routeScope())
        } finally {
            direct.close()
        }

        val preferences = PreferenceSnapshot.of(
            ProxyEnabledKey to true, ProxyHostKey to "proxy.example", ProxyPortKey to 8080,
            ProxyUsernameKey to "proxy-user", ProxyPasswordKey to "proxy-secret",
        )
        val proxied = DesktopNetworkOwner { preferences }
        try {
            proxied.applyNetworkSettings(preferences)
            assertEquals("HTTP:proxy.example:8080", proxied.routeScope())
            assertFalse(proxied.routeScope().orEmpty().contains("proxy-user"))
            assertFalse(proxied.routeScope().orEmpty().contains("proxy-secret"))
        } finally {
            proxied.close()
        }
    }

    /**
     * A pool of one route always hands out that route, so a cached URL can be keyed on it; a larger pool hands each
     * request a different egress, so no scope can say where a cached URL would be read and the caller must not cache.
     */
    @Test fun onlyASingleRoutePoolNamesTheEgressARotationUses() {
        val empty = DesktopRotatingProxySelector()
        assertNull(empty.singleRoute())

        val single = DesktopRotatingProxySelector()
        single.loadProxies(listOf("only.example" to 3128))
        assertEquals("only.example:3128", single.singleRoute())
        assertEquals("only.example:3128", single.singleRoute())

        val rotating = DesktopRotatingProxySelector()
        rotating.loadProxies(listOf("first.example" to 8080, "second.example" to 8080))
        assertNull(rotating.singleRoute())

        // The pool the app starts from is cleared, and a cleared pool names no route either.
        rotating.loadProxies(emptyList())
        assertNull(rotating.singleRoute())
    }

    /**
     * The scope the resolver cache keys on: the direct route, a static proxy's endpoint, a single-route rotation, and —
     * for a rotation that moves between egresses — nothing at all, which is what makes the cache bypass itself.
     */
    @Test fun theRouteScopeNamesTheEgressAndIsNullWhenRotationMovesBetweenThem() {
        val configured = config(proxy("static.example"))

        assertEquals(DIRECT_ROUTE_SCOPE, desktopRouteScope(configured = null, rotating = false, singleRotationRoute = null))
        assertEquals("HTTP:static.example:8080", desktopRouteScope(configured, rotating = false, singleRotationRoute = null))
        // A pool of one route is named even though a static proxy is also saved: rotation is what the requests use.
        assertEquals("rotation:only.example:3128", desktopRouteScope(configured, rotating = true, singleRotationRoute = "only.example:3128"))
        // A pool that rotates between routes knows no egress for the media read, so nothing may be cached.
        assertNull(desktopRouteScope(configured, rotating = true, singleRotationRoute = null))
        assertNull(desktopRouteScope(configured = null, rotating = true, singleRotationRoute = null))
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
