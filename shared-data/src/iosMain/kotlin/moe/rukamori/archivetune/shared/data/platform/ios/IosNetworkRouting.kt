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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.domain.playback.STREAM_BLOCKED_MESSAGE
import moe.rukamori.archivetune.shared.domain.settings.IpRotationEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.PROXY_LOCAL_HOST_EXCEPTIONS
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ProxyRoute
import moe.rukamori.archivetune.shared.domain.settings.ProxyRouteType
import moe.rukamori.archivetune.shared.domain.settings.StreamBypassProxyKey
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
    private var bypassStreams = false

    @Volatile
    private var pool: IosProxyRotation? = null

    /**
     * The saved route and its bypass switch for the next stream. The flag is kept apart from the route
     * because it survives a disabled static proxy: a saved bypass stays in force while rotation runs.
     */
    fun updateStreamRoute(route: ProxyRoute?, bypass: Boolean) {
        savedRoute = route
        bypassStreams = bypass
    }

    /** The pool a stream consults first, or null while rotation is off. */
    fun updateRotation(rotation: IosProxyRotation?) {
        pool = rotation
    }

    /**
     * The route the next stream connection opens with. The bypass switch beats everything — the desktop
     * selector sends bypassed streams to the system route even while it rotates — an empty rotation pool
     * falls back to the saved route, and no route at all keeps the system connection.
     */
    fun routeForStream(): ProxyRoute? {
        if (bypassStreams) return null
        pool?.next()?.let { return it }
        return savedRoute
    }

    /**
     * True while rotation is on but has no member and no saved route to fall back on: a stream opened now
     * would leak off the real address, so the host transport must fail it rather than open it direct.
     */
    fun streamBlocked(): Boolean =
        !bypassStreams && savedRoute == null && pool?.let { !it.hasMembers() } == true

    /**
     * True when a stream must open through the host transport rather than the engine's own URL fetcher:
     * a route or a rotation is live. The blocked case counts too — [applyTo] then refuses the connection
     * instead of answering the direct route the engine's fetcher would have taken.
     */
    fun mustProxy(): Boolean = !bypassStreams && (savedRoute != null || pool != null)

    /**
     * Applies [routeForStream] to a session configuration and answers the chosen route, so the transfer that
     * opens it can report a broken connection back to the pool. Throws [ByteSourceException] when rotation is
     * on with nothing to route through, which fails the open instead of leaking it direct.
     */
    fun applyTo(configuration: NSURLSessionConfiguration): ProxyRoute? {
        if (streamBlocked()) {
            throw ByteSourceException(STREAM_BLOCKED_MESSAGE)
        }
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
class IosNetworkOwner internal constructor(
    private val preferences: () -> PreferenceSnapshot?,
    private val report: (String) -> Unit,
    /** The pool behind the stream routing — tests inject one whose fetch a deferred controls. */
    private val rotation: IosProxyRotation,
) {
    constructor(preferences: () -> PreferenceSnapshot?, report: (String) -> Unit) :
        this(preferences, report, IosProxyRotation())

    /** The route the running Darwin engine was built with, read once when the session opened. */
    val runningRoute: ProxyRoute? = preferences()?.proxyRoute()

    private val rotationLock = Mutex()

    @Volatile
    private var rotating = false

    private val _rotationBlocked = MutableStateFlow(false)

    /**
     * Whether an installed rotation has no member and no saved route to answer with — every stream open
     * then fails closed, so surfaces name the rotation state instead of a generic failure. A flow
     * because a fetch filling the pool or a disable changes it without a recomposition of the page.
     */
    val rotationBlocked: StateFlow<Boolean> get() = _rotationBlocked

    /** Republishes the routing holder's blocked state after every write that can change it. */
    private fun publishBlocked() {
        _rotationBlocked.value = IosStreamProxyRouting.streamBlocked()
    }

    init {
        // The stream transport reads its route per connection, so it starts on the saved one and never has to
        // wait for the first settings commit. The bypass switch is read on its own because it outlives a
        // disabled static route. A saved-on rotation installs its still-empty pool right away: until start()
        // repopulates it the pool blocks every stream open rather than leaking one off the real address.
        IosStreamProxyRouting.updateStreamRoute(
            runningRoute,
            preferences()?.get(StreamBypassProxyKey) == true,
        )
        rotating = preferences()?.get(IpRotationEnabledKey) == true
        IosStreamProxyRouting.updateRotation(if (rotating) rotation else null)
        publishBlocked()
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
        IosStreamProxyRouting.updateStreamRoute(
            snapshot?.proxyRoute(),
            snapshot?.get(StreamBypassProxyKey) == true,
        )
        publishBlocked()
    }

    /** The members the rotation pool currently counts live; 0 while rotation is off. */
    fun activeRotationCount(): Int = if (rotating) rotation.activeCount() else 0


    /**
     * Re-installs a rotation the user left enabled, the way the desktop owner's start does: the fetch and the
     * validations are network-bound, so they run off the launch path. The pool was already installed empty at
     * init, so until the fetch populates it every stream open fails closed instead of leaking direct.
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
     * enable/refresh contract. While the setting is enabled the rotation stays installed even when a fetch
     * finds nothing or every member sits in its failure cooldown: [IosStreamProxyRouting] then blocks or
     * cools each open rather than leaking it direct, and only the disable branch above ever clears the pool.
     * An enable that still counts no live member answers 0 so the page can revert the toggle — the disable
     * that revert commits is the explicit clear. Rotation changes which proxy the next stream connection
     * opens with; it never re-routes the engine's pinned session.
     */
    suspend fun applyIpRotation(enabled: Boolean, refresh: Boolean): Int = rotationLock.withLock {
        if (!enabled) {
            disableRotation()
            return@withLock 0
        }
        // Install before the fetch starts: the setting is already committed, so every stream opened
        // while the fetch runs must hit the pool — a starved one blocks the open rather than letting
        // it out direct. A cancelled or failed fetch leaves the install in place, and only the
        // disable branch above ever clears it.
        rotating = true
        IosStreamProxyRouting.updateRotation(rotation)
        publishBlocked()
        rotation.fetchAndLoad { preferences()?.proxyRoute() }
        publishBlocked()
        rotation.activeCount()
    }

    private fun disableRotation() {
        rotating = false
        IosStreamProxyRouting.updateRotation(null)
        rotation.clear()
        publishBlocked()
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
