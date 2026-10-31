package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import java.io.IOException
import java.net.Authenticator
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.net.URLConnection
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.innertube.YouTube
import moe.rukamori.archivetune.shared.domain.settings.IpRotationEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ProxyEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyHostKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyPasswordKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyPortKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyTypeKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyUsernameKey
import moe.rukamori.archivetune.shared.domain.settings.StreamBypassProxyKey
import moe.rukamori.archivetune.shared.domain.settings.parseProxyHostInput
import moe.rukamori.archivetune.shared.ui.settings.ProxyConnectionResult
import okhttp3.ConnectionPool
import okhttp3.Credentials

internal const val PROXY_PROBE_URL = "https://music.youtube.com/generate_204"

internal fun desktopRotationReady(desktopCount: Int, innerTubeCount: Int): Boolean =
    desktopCount > 0 && innerTubeCount > 0

/** Process routing is installed before any desktop provider client is constructed. */
internal class DesktopNetworkOwner(
    private val preferences: () -> PreferenceSnapshot?,
) {
    private val previousSelector = ProxySelector.getDefault()
    private val previousAuthenticator = Authenticator.getDefault()
    private val lock = Mutex()
    private var startup: Job? = null
    private var installed = false
    private var closed = false
    @Volatile private var config: DesktopProxyConfig? = null
    @Volatile private var rotating = false
    private val rotation = DesktopRotatingProxySelector()
    private val connections = ConnectionPool()
    val selector = DesktopProxySelector({ config }, { rotation.takeIf { rotating } }, previousSelector)
    private val javaAuthenticator = object : Authenticator() {
        override fun getPasswordAuthentication(): PasswordAuthentication? {
            val configured = config ?: return null
            if (!configured.hasCredentials) return null
            val socks = requestingProtocol?.startsWith("SOCKS", ignoreCase = true) == true
            if (configured.proxy.type() != if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP) return null
            if (!socks && requestorType != RequestorType.PROXY) return null
            if (!configured.matches(requestingHost, requestingPort) &&
                !configured.matches(requestingSite?.hostAddress, requestingPort)
            ) return null
            return PasswordAuthentication(configured.username, configured.password.orEmpty().toCharArray())
        }
    }

    suspend fun applyNetworkSettings(preferences: PreferenceSnapshot) = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!closed)
            config = desktopProxyConfig(preferences)
            if (!installed) {
                ProxySelector.setDefault(selector)
                Authenticator.setDefault(javaAuthenticator)
                installed = true
            }
            applyInnerTubeProxy()
            connections.evictAll()
        }
    }

    /** HTTP/SOCKS and scoped HTTP 407 authentication share one dynamic selector, including after settings edits. */
    fun httpClient(): HttpClient = desktopProxyHttpClient(selector, connections) { config }

    fun start(scope: CoroutineScope) {
        startup = scope.launch {
            if (preferences()?.get(IpRotationEnabledKey) != true) return@launch
            try {
                if (applyIpRotation(enabled = true, refresh = false) == 0) {
                    desktopDiagnostic("IP rotation restore found no validated proxy routes")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                desktopDiagnostic("IP rotation restore failed: ${failure.javaClass.simpleName}")
            }
        }
    }

    fun activeRotationCount(): Int = if (rotating) rotation.activeCount() else 0

    suspend fun applyIpRotation(enabled: Boolean, refresh: Boolean): Int = withContext(Dispatchers.IO) {
        lock.withLock {
            check(!closed)
            if (!enabled) {
                disableRotation()
                config = preferences()?.let(::desktopProxyConfig)
                applyInnerTubeProxy()
                return@withLock 0
            }
            try {
                // Core deliberately keeps its selector private. Use its public rotation lifecycle for InnerTube,
                // and the bounded JVM pool for other clients; neither stages credentials in the other pool.
                coroutineScope {
                    val desktop = async {
                        if (refresh && rotation.activeCount() > 1) rotation.rotate() else rotation.fetchAndLoad()
                    }
                    val core = async {
                        if (refresh) YouTube.refreshIpRotation() else YouTube.enableIpRotation()
                    }
                    desktop.await()
                    core.await()
                }
                val count = rotation.activeCount()
                if (!desktopRotationReady(count, YouTube.ipRotationActiveCount.value)) {
                    // An empty pool must not hide a working saved static proxy behind a direct-only selector.
                    disableRotation()
                    return@withLock 0
                }
                rotating = true
                connections.evictAll()
                count
            } catch (failure: Throwable) {
                disableRotation()
                throw failure
            }
        }
    }

    suspend fun close() {
        startup?.cancelAndJoin()
        lock.withLock {
            if (closed) return
            closed = true
            disableRotation()
            if (ProxySelector.getDefault() === selector) ProxySelector.setDefault(previousSelector)
            if (Authenticator.getDefault() === javaAuthenticator) Authenticator.setDefault(previousAuthenticator)
            if (YouTube.proxy != null) YouTube.proxy = null
            if (YouTube.proxyUsername != null) YouTube.proxyUsername = null
            if (YouTube.proxyPassword != null) YouTube.proxyPassword = null
            YouTube.streamBypassProxy = false
            connections.evictAll()
            config = null
        }
    }

    private fun disableRotation() {
        rotating = false
        rotation.loadProxies(emptyList())
        YouTube.disableIpRotation()
        connections.evictAll()
    }

    private fun applyInnerTubeProxy() {
        val configured = config
        if (YouTube.proxy != configured?.proxy) YouTube.proxy = configured?.proxy
        if (YouTube.proxyUsername != configured?.username) YouTube.proxyUsername = configured?.username
        if (YouTube.proxyPassword != configured?.password) YouTube.proxyPassword = configured?.password
        YouTube.streamBypassProxy = configured?.bypassStreams == true
    }

    suspend fun testProxy(preferences: PreferenceSnapshot): ProxyConnectionResult = withContext(Dispatchers.IO) {
        val configured = desktopProxyConfig(preferences) ?: return@withContext ProxyConnectionResult.INVALID_CONFIGURATION
        val fixed = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOf(configured.proxy)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }
        val parent = desktopProxyHttpClient(fixed) { configured }
        val client = parent.config {
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000L
                connectTimeoutMillis = 6_000L
                socketTimeoutMillis = 8_000L
            }
        }
        try {
            when (client.get(PROXY_PROBE_URL).status.value) {
                in 200..399 -> ProxyConnectionResult.CONNECTED
                407 -> ProxyConnectionResult.AUTHENTICATION
                else -> ProxyConnectionResult.FAILED
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            when (failure) {
                is java.net.UnknownHostException -> ProxyConnectionResult.DNS_FAILURE
                is java.net.ConnectException -> ProxyConnectionResult.UNREACHABLE
                is java.net.SocketTimeoutException, is io.ktor.client.plugins.HttpRequestTimeoutException,
                is io.ktor.client.network.sockets.ConnectTimeoutException,
                is io.ktor.client.network.sockets.SocketTimeoutException -> ProxyConnectionResult.TIMEOUT
                else -> ProxyConnectionResult.FAILED
            }
        } finally {
            client.close()
            parent.close()
        }
    }
}

internal class DesktopProxyConfig(
    val proxy: Proxy,
    val username: String?,
    val password: String?,
    val bypassStreams: Boolean,
) {
    val hasCredentials: Boolean get() = !username.isNullOrEmpty() && !password.isNullOrEmpty()

    fun matches(candidate: Proxy?): Boolean = candidate == proxy

    fun matches(host: String?, port: Int): Boolean {
        val address = proxy.address() as? InetSocketAddress ?: return false
        return address.port == port && address.hostString.equals(host, ignoreCase = true)
    }

    override fun toString(): String = "DesktopProxyConfig(type=${proxy.type()}, auth=$hasCredentials)"
}

internal fun desktopProxyConfig(preferences: PreferenceSnapshot): DesktopProxyConfig? {
    if (!preferences[ProxyEnabledKey, false]) return null
    val parsed = parseProxyHostInput(preferences[ProxyHostKey, ""]) ?: return null
    val port = parsed.port ?: preferences[ProxyPortKey, 8080]
    if (port !in 1..65535) return null
    return DesktopProxyConfig(
        Proxy(
            if (preferences[ProxyTypeKey, "HTTP"] == "SOCKS") Proxy.Type.SOCKS else Proxy.Type.HTTP,
            InetSocketAddress.createUnresolved(parsed.host, port),
        ),
        preferences[ProxyUsernameKey]?.trim()?.takeIf(String::isNotEmpty),
        preferences[ProxyPasswordKey]?.takeIf(String::isNotEmpty),
        preferences[StreamBypassProxyKey, false],
    )
}

internal fun desktopProxyHttpClient(
    selector: ProxySelector,
    connections: ConnectionPool? = null,
    configured: () -> DesktopProxyConfig?,
): HttpClient = HttpClient(OkHttp) {
    engine {
        config {
            proxySelector(selector)
            connections?.let { connectionPool(it) }
            proxyAuthenticator { route, response ->
                val proxy = configured() ?: return@proxyAuthenticator null
                if (response.code != 407 || !proxy.hasCredentials || !proxy.matches(route?.proxy) ||
                    proxy.proxy.type() != Proxy.Type.HTTP
                ) return@proxyAuthenticator null
                val credential = Credentials.basic(proxy.username.orEmpty(), proxy.password.orEmpty(), Charsets.UTF_8)
                if (response.request.header("Proxy-Authorization") == credential) return@proxyAuthenticator null
                response.request.newBuilder().header("Proxy-Authorization", credential).build()
            }
        }
    }
}

internal class DesktopProxySelector(
    private val configured: () -> DesktopProxyConfig?,
    private val rotation: () -> ProxySelector?,
    private val system: ProxySelector?,
) : ProxySelector() {
    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri)
        if (uri.scheme?.lowercase() !in setOf("http", "https", "ws", "wss")) return systemSelect(uri)
        if (isDesktopLocalHost(uri.host)) return listOf(Proxy.NO_PROXY)
        rotation()?.let { return it.select(uri) }
        return configured()?.let { listOf(it.proxy) } ?: systemSelect(uri)
    }

    fun selectForStream(uri: URI): List<Proxy> =
        if (configured()?.bypassStreams == true) systemSelect(uri) else select(uri)

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val rotating = rotation()
        if (rotating != null) rotating.connectFailed(uri, sa, ioe)
        else if (configured() == null) system?.connectFailed(uri, sa, ioe)
    }

    private fun systemSelect(uri: URI): List<Proxy> =
        system?.select(uri)?.takeIf { it.isNotEmpty() } ?: listOf(Proxy.NO_PROXY)
}

private fun isDesktopLocalHost(rawHost: String?): Boolean {
    val host = rawHost?.trim()?.removePrefix("[")?.removeSuffix("]")?.lowercase().orEmpty()
    if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
    if (!host.matches(Regex("\\d{1,3}(\\.\\d{1,3}){3}")) && ':' !in host) return false
    val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
    return address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress ||
        address.isAnyLocalAddress || address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
}

/** Stream bypass affects payloads only; the provider/catalogue requests still use the ordinary selector. */
internal fun openDesktopStreamConnection(url: URL): URLConnection {
    val selector = ProxySelector.getDefault() as? DesktopProxySelector ?: return url.openConnection()
    val proxy = selector.selectForStream(url.toURI()).firstOrNull() ?: Proxy.NO_PROXY
    return url.openConnection(proxy)
}

internal class DesktopRotatingProxySelector : ProxySelector() {
    private class Pool(val proxies: Array<Proxy>, val deadUntil: AtomicLongArray)
    private val pool = AtomicReference(Pool(emptyArray(), AtomicLongArray(0)))
    private val index = AtomicInteger(0)

    fun loadProxies(configs: List<Pair<String, Int>>) {
        pool.set(Pool(
            Array(configs.size) { Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(configs[it].first, configs[it].second)) },
            AtomicLongArray(configs.size),
        ))
        index.set(0)
    }

    fun activeCount(): Int {
        val current = pool.get()
        val now = System.currentTimeMillis()
        return current.proxies.indices.count { current.deadUntil.get(it) <= now }
    }

    fun rotate() {
        if (pool.get().proxies.isNotEmpty()) index.incrementAndGet()
    }

    override fun select(uri: URI?): List<Proxy> {
        val current = pool.get()
        if (current.proxies.isEmpty()) return listOf(Proxy.NO_PROXY)
        val start = Math.floorMod(index.getAndIncrement(), current.proxies.size)
        val now = System.currentTimeMillis()
        for (offset in current.proxies.indices) {
            val candidate = (start + offset) % current.proxies.size
            if (current.deadUntil.get(candidate) <= now) return listOf(current.proxies[candidate])
        }
        // A cooled-down pool is not permission to leak a request through a direct connection.
        return listOf(current.proxies[start])
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        val current = pool.get()
        current.proxies.indices.firstOrNull { current.proxies[it].address() == sa }?.let {
            current.deadUntil.set(it, System.currentTimeMillis() + 60_000L)
        }
    }

    suspend fun fetchAndLoad() {
        val bootstrap = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(
            URI.create("https://raw.githubusercontent.com/TheSpeedX/PROXY-List/master/http.txt"),
        ).timeout(Duration.ofSeconds(20)).GET().build()
        val configs = runCatching {
            bootstrap.use { client ->
                client.send(request, HttpResponse.BodyHandlers.ofString())
                    .takeIf { it.statusCode() == 200 }?.body()?.lineSequence()?.mapNotNull { line ->
                        val trimmed = line.trim()
                        val colon = trimmed.lastIndexOf(':')
                        if (colon < 0) return@mapNotNull null
                        val host = trimmed.substring(0, colon)
                        val port = trimmed.substring(colon + 1).toIntOrNull() ?: return@mapNotNull null
                        if (port !in 1..65535 || host.isBlank()) null else host to port
                    }?.take(60)?.toList().orEmpty()
            }
        }.getOrDefault(emptyList())
        val live = coroutineScope {
            val semaphore = Semaphore(12)
            configs.map { config ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        if (isUsable(config)) config else null
                    }
                }
            }.awaitAll().filterNotNull().take(20)
        }
        loadProxies(live)
    }

    private fun isUsable(config: Pair<String, Int>): Boolean = runCatching {
        val client = JdkHttpClient.newBuilder()
            .proxy(ProxySelector.of(InetSocketAddress.createUnresolved(config.first, config.second)))
            .connectTimeout(Duration.ofSeconds(4)).build()
        val request = HttpRequest.newBuilder(URI.create(PROXY_PROBE_URL)).timeout(Duration.ofSeconds(6)).GET().build()
        client.use { it.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() in 200..399 }
    }.getOrDefault(false)
}
