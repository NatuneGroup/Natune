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
import kotlinx.coroutines.CancellationException
import moe.rukamori.archivetune.shared.domain.settings.PROXY_LOCAL_HOST_EXCEPTIONS
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ProxyRoute
import moe.rukamori.archivetune.shared.domain.settings.ProxyRouteType
import moe.rukamori.archivetune.shared.domain.settings.proxyRoute
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLCredentialPersistenceNone
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeUseCredential

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
        if (!space.isProxy || challenge.previousFailureCount >= 2L) {
            completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, null)
        } else {
            completionHandler(
                NSURLSessionAuthChallengeUseCredential,
                NSURLCredential.credentialWithUser(
                    user = username,
                    password = password,
                    persistence = NSURLCredentialPersistenceNone,
                ),
            )
        }
    }
}

/**
 * The iOS network owner. The Darwin engine binds its proxy when it creates its NSURLSession, so one process uses one
 * pinned route: the saved static route at launch. Rotation is therefore not offered here — it would need a route per
 * request — and an edit made while running is stored and reported as applying to the next launch, never pretended to
 * be live. Audio streams are read through the player's own session, which keeps the system route.
 */
class IosNetworkOwner(
    private val preferences: () -> PreferenceSnapshot?,
    private val report: (String) -> Unit,
) {
    /** The route the running Darwin engine was built with, read once when the session opened. */
    val runningRoute: ProxyRoute? = preferences()?.proxyRoute()

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
