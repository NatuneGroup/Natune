@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package moe.rukamori.archivetune.shared.data.platform.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.DarwinClientEngineConfig
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.settings.IpRotationEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PROXY_LOCAL_HOST_EXCEPTIONS
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ProxyRoute
import moe.rukamori.archivetune.shared.domain.settings.ProxyRouteType
import moe.rukamori.archivetune.shared.domain.settings.proxyRoute
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistence
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.create

/** The sanitized outcome of one proxy probe; the settings page maps it to its own result type. */
enum class IosProxyTestResult { CONNECTED, INVALID_CONFIGURATION, AUTHENTICATION, TIMEOUT, UNREACHABLE, FAILED }

private const val PROXY_PROBE_URL = "https://music.youtube.com/generate_204"
private const val PROXY_KEY_HTTP_ENABLE = "HTTPEnable"
private const val PROXY_KEY_HTTP_HOST = "HTTPProxy"
private const val PROXY_KEY_HTTP_PORT = "HTTPPort"
private const val PROXY_KEY_HTTPS_ENABLE = "HTTPSEnable"
private const val PROXY_KEY_HTTPS_HOST = "HTTPSProxy"
private const val PROXY_KEY_HTTPS_PORT = "HTTPSPort"
private const val PROXY_KEY_SOCKS_ENABLE = "SOCKSEnable"
private const val PROXY_KEY_SOCKS_HOST = "SOCKSProxy"
private const val PROXY_KEY_SOCKS_PORT = "SOCKSPort"
private const val PROXY_KEY_EXCEPTIONS = "ExceptionsList"
private const val PROXY_KEY_EXCLUDE_SIMPLE_HOSTNAMES = "ExcludeSimpleHostnames"

/**
 * The CFNetwork proxy dictionary of one route. CFNetwork reads it when the NSURLSession is created, which is why a
 * route cannot be changed for a session that already exists. Local hosts are excluded here rather than per request,
 * because the engine has no per-request hook. The catalog and the proxy probe are https, and CFNetwork reads the
 * HTTPS keys for https URLs, so an HTTP proxy is named for both schemes.
 */
fun iosProxySessionDictionary(route: ProxyRoute): Map<Any?, Any?> {
    val entries = mutableListOf<Pair<Any?, Any?>>()
    when (route.type) {
        ProxyRouteType.HTTP -> {
            entries += PROXY_KEY_HTTP_ENABLE to 1
            entries += PROXY_KEY_HTTP_HOST to route.host
            entries += PROXY_KEY_HTTP_PORT to route.port
            entries += PROXY_KEY_HTTPS_ENABLE to 1
            entries += PROXY_KEY_HTTPS_HOST to route.host
            entries += PROXY_KEY_HTTPS_PORT to route.port
        }
        ProxyRouteType.SOCKS -> {
            entries += PROXY_KEY_SOCKS_ENABLE to 1
            entries += PROXY_KEY_SOCKS_HOST to route.host
            entries += PROXY_KEY_SOCKS_PORT to route.port
        }
    }
    entries += PROXY_KEY_EXCEPTIONS to PROXY_LOCAL_HOST_EXCEPTIONS
    entries += PROXY_KEY_EXCLUDE_SIMPLE_HOSTNAMES to 1
    return entries.toMap()
}

/**
 * Applies [route] to one Darwin engine. The session dictionary carries the route itself; the credentials can only be
 * answered per authentication challenge, because CFNetwork keeps proxy passwords out of the configuration. A SOCKS
 * proxy that demands a username and password is not expressible this way: the challenge path is best effort and the
 * settings page reports that limit.
 */
fun DarwinClientEngineConfig.applyIosProxyRoute(route: ProxyRoute?) {
    if (route == null) return
    configureSession { connectionProxyDictionary = iosProxySessionDictionary(route) }
    if (!route.hasCredentials) return
    val username = route.username.orEmpty()
    val password = route.password.orEmpty()
    handleChallenge { _, _, challenge, completionHandler ->
        val space = challenge.protectionSpace
        // One retry absorbs a proxy that asks twice; a third refusal is the proxy rejecting these credentials.
        if (!space.isProxy() || challenge.previousFailureCount >= 2L) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        } else {
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                // The Foundation category initializer is bound as a companion create(), not credentialWithUser.
                NSURLCredential.create(
                    user = username,
                    password = password,
                    persistence = NSURLCredentialPersistence.NSURLCredentialPersistenceNone,
                ),
            )
        }
    }
}

/**
 * The process-wide stream route, the iOS analog of the desktop's default ProxySelector. The Darwin engine
 * keeps a session's route for the session's life, but every playback transfer builds its own ephemeral
 * session, so the saved route and the rotating pool reach the next connection through this holder. It is
 * process state because the byte sources that read it sit below the session graph's reach; a rebuilt graph
 * writes it again, which is the same constraint the engine's pinned route has.
 */
internal object IosStreamProxyRouting {
    @Volatile
    private var savedRoute: ProxyRoute? = null

    @Volatile
    private var pool: IosProxyRotation? = null

    /** The saved route the next stream honours; its bypass switch takes the stream traffic off the proxy. */
    fun updateStreamRoute(route: ProxyRoute?) {
        savedRoute = route
    }

    /** The pool a stream consults first, or null while rotation is off. */
    fun updateRotation(rotation: IosProxyRotation?) {
        pool = rotation
    }

    /**
     * The route the next stream connection opens with. The bypass switch beats everything — the desktop
     * selector sends bypassed streams to the system route even while it rotates — rotation beats the saved
     * static route, and no route at all keeps the system connection.
     */
    fun routeForStream(): ProxyRoute? {
        val saved = savedRoute
        if (saved?.bypassStreams == true) return null
        pool?.next()?.let { return it }
        return saved
    }

    /**
     * Applies [routeForStream] to a session configuration and answers the chosen route, so the transfer that
     * opens it can report a broken connection back to the pool.
     */
    fun applyTo(configuration: NSURLSessionConfiguration): ProxyRoute? {
        val route = routeForStream() ?: return null
        configuration.connectionProxyDictionary = iosProxySessionDictionary(route)
        return route
    }

    /** A broken transfer cools its pool member; a static or rotating-disabled route has no pool entry to cool. */
    fun reportFailure(route: ProxyRoute) {
        pool?.markFailed(route)
    }
}

/**
 * The iOS network owner. The Darwin engine binds its proxy when it creates its NSURLSession, so the engine's
 * requests use one pinned route: the saved static route at launch, with an edit reported as applying to the next
 * launch, never pretended to be live. Playback audio is different — every stream transfer builds a fresh ephemeral
 * session, so its route comes from [IosStreamProxyRouting] at connection time: the saved route honours the bypass
 * switch live, and an enabled rotation serves the next validated pool member to each new stream connection. The
 * rotating pool can never take the catalogue with it, so rotation on this host moves stream traffic only.
 */
class IosNetworkOwner(
    private val preferences: () -> PreferenceSnapshot?,
    private val report: (String) -> Unit,
) {
    /** The route the running Darwin engine was built with, read once when the session opened. */
    val runningRoute: ProxyRoute? = preferences()?.proxyRoute()

    private val rotationLock = Mutex()
    private val rotation = IosProxyRotation()

    @Volatile
    private var rotating = false

    init {
        // The stream transport reads its route per connection, so it starts on the saved one and never has to
        // wait for the first settings commit. A leftover pool from a replaced graph is dropped the same way.
        IosStreamProxyRouting.updateStreamRoute(runningRoute)
        IosStreamProxyRouting.updateRotation(null)
    }

    fun configure(config: DarwinClientEngineConfig) = config.applyIosProxyRoute(runningRoute)

    /**
     * True when the saved route differs from the running one in its address or its credentials, because both are
     * pinned when the session starts: the challenge handler answers with the credentials it was built with. The
     * stream-bypass switch has no effect on a running engine, so changing it never asks for a restart.
     */
    fun savedRouteChanged(snapshot: PreferenceSnapshot): Boolean =
        snapshot.proxyRoute()?.pinnedFields() != runningRoute?.pinnedFields()

    private fun ProxyRoute.pinnedFields(): ProxyRoute = copy(bypassStreams = false)

    /** One diagnostic line without credentials, for the session log. */
    fun describe(): String =
        runningRoute?.let { "network: pinned proxy ${it.endpointKey}" } ?: "network: system route"

    /**
     * Mirrors [snapshot]'s route into the stream transport. The next stream connection takes it — unlike the
     * engine the stream sessions are rebuilt per request, so this is the one route an edit moves live.
     */
    fun applyStreamRoute(snapshot: PreferenceSnapshot?) {
        IosStreamProxyRouting.updateStreamRoute(snapshot?.proxyRoute())
    }

    /** The members the rotation pool currently counts live; 0 while rotation is off. */
    fun activeRotationCount(): Int = if (rotating) rotation.activeCount() else 0

    /**
     * Re-installs a rotation the user left enabled, the way the desktop owner's start does: the fetch and the
     * validations are network-bound, so they run off the launch path, and an empty pool leaves rotation off
     * rather than making the toggle lie.
     */
    fun start(scope: CoroutineScope) {
        scope.launch {
            if (preferences()?.get(IpRotationEnabledKey) != true) return@launch
            try {
                if (applyIpRotation(enabled = true, refresh = false) == 0) {
                    report("ip rotation restore found no validated proxy routes")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                report("ip rotation restore failed: ${failure::class.simpleName}")
            }
        }
    }

    /**
     * Enables, refreshes or stops the rotating pool and answers its live size, for the settings page's
     * enable/refresh contract. A refresh refetches — the refresh action exists to replace the pool. An enable
     * that validates nothing answers 0 so the page can revert the toggle. Rotation changes which proxy the
     * next stream connection opens with; it never re-routes the engine's pinned session.
     */
    suspend fun applyIpRotation(enabled: Boolean, refresh: Boolean): Int = rotationLock.withLock {
        if (!enabled) {
            disableRotation()
            return@withLock 0
        }
        try {
            rotation.fetchAndLoad { preferences()?.proxyRoute() }
            val count = rotation.activeCount()
            if (count == 0) {
                disableRotation()
                return@withLock 0
            }
            rotating = true
            IosStreamProxyRouting.updateRotation(rotation)
            count
        } catch (failure: Throwable) {
            disableRotation()
            throw failure
        }
    }

    private fun disableRotation() {
        rotating = false
        IosStreamProxyRouting.updateRotation(null)
        rotation.clear()
    }

    /**
     * Probes [snapshot]'s saved route with a one-shot client that carries it. The probe really leaves the device
     * through the proxy, so its answer is the proxy's, not a stand-in.
     */
    suspend fun testProxy(snapshot: PreferenceSnapshot): IosProxyTestResult {
        val route = snapshot.proxyRoute() ?: return IosProxyTestResult.INVALID_CONFIGURATION
        val probe = HttpClient(Darwin) {
            engine { applyIosProxyRoute(route) }
            install(HttpTimeout) {
                requestTimeoutMillis = 8_000L
                connectTimeoutMillis = 6_000L
                socketTimeoutMillis = 8_000L
            }
        }
        return try {
            when (val status = probe.get(PROXY_PROBE_URL).status.value) {
                in 200..399 -> IosProxyTestResult.CONNECTED
                407 -> IosProxyTestResult.AUTHENTICATION
                else -> {
                    report("proxy test returned HTTP $status")
                    IosProxyTestResult.FAILED
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            when (failure) {
                is HttpRequestTimeoutException, is SocketTimeoutException, is ConnectTimeoutException ->
                    IosProxyTestResult.TIMEOUT
                else -> {
                    report("proxy test failed: ${failure::class.simpleName}")
                    IosProxyTestResult.UNREACHABLE
                }
            }
        } finally {
            probe.close()
        }
    }
}
