package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.domain.settings.PROXY_LOCAL_HOST_EXCEPTIONS
import moe.rukamori.archivetune.shared.domain.settings.PreferenceKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.ProxyEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyHostKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyPasswordKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyPortKey
import moe.rukamori.archivetune.shared.domain.settings.ProxyRoute
import moe.rukamori.archivetune.shared.domain.settings.ProxyRouteType
import moe.rukamori.archivetune.shared.domain.settings.ProxyUsernameKey
import moe.rukamori.archivetune.shared.domain.settings.StreamBypassProxyKey
import platform.Foundation.NSURLSessionConfiguration

class IosNetworkRoutingTest {
    private fun httpRoute(
        host: String = "proxy.example",
        port: Int = 3128,
        username: String? = null,
        password: String? = null,
    ) = ProxyRoute(ProxyRouteType.HTTP, host, port, username, password)

    private fun snapshot(vararg entries: Pair<PreferenceKey<*>, Any>) = PreferenceSnapshot.of(*entries)

    private fun enabled(host: String = "proxy.example", port: Int = 3128) =
        snapshot(ProxyEnabledKey to true, ProxyHostKey to host, ProxyPortKey to port)

    @Test
    fun anHttpRouteNamesTheProxyForBothSchemesAndNoSocksKey() {
        val dictionary = iosProxySessionDictionary(httpRoute())
        assertEquals(1, dictionary["HTTPEnable"])
        assertEquals("proxy.example", dictionary["HTTPProxy"])
        assertEquals(3128, dictionary["HTTPPort"])
        assertEquals(1, dictionary["HTTPSEnable"])
        assertEquals("proxy.example", dictionary["HTTPSProxy"])
        assertEquals(3128, dictionary["HTTPSPort"])
        assertNull(dictionary["SOCKSEnable"])
        assertNull(dictionary["SOCKSProxy"])
        assertNull(dictionary["SOCKSPort"])
    }

    @Test
    fun aSocksRouteEnablesOnlyTheSocksProxyKeys() {
        val route = httpRoute(host = "socks.example", port = 1080).copy(type = ProxyRouteType.SOCKS)
        val dictionary = iosProxySessionDictionary(route)
        assertEquals(1, dictionary["SOCKSEnable"])
        assertEquals("socks.example", dictionary["SOCKSProxy"])
        assertEquals(1080, dictionary["SOCKSPort"])
        assertNull(dictionary["HTTPEnable"])
        assertNull(dictionary["HTTPProxy"])
        assertNull(dictionary["HTTPPort"])
        assertNull(dictionary["HTTPSEnable"])
        assertNull(dictionary["HTTPSProxy"])
        assertNull(dictionary["HTTPSPort"])
    }

    @Test
    fun everyRouteExcludesLocalHosts() {
        val dictionary = iosProxySessionDictionary(httpRoute())
        assertEquals(PROXY_LOCAL_HOST_EXCEPTIONS, dictionary["ExceptionsList"])
        assertEquals(1, dictionary["ExcludeSimpleHostnames"])
    }

    @Test
    fun credentialsNeverReachTheSessionDictionary() {
        val dictionary = iosProxySessionDictionary(httpRoute(username = "user", password = "secret"))
        assertTrue(dictionary.values.none { it == "user" || it == "secret" })
        assertTrue(dictionary.values.none { it is String && "secret" in it })
    }

    @Test
    fun theRunningRouteIsReadOnceAndItsAddressOrCredentialsPinTheSession() {
        val owner = IosNetworkOwner({ enabled() }) {}
        assertEquals("http://proxy.example:3128", requireNotNull(owner.runningRoute).endpointKey)
        assertFalse(owner.savedRouteChanged(enabled()))
        assertTrue(
            owner.savedRouteChanged(
                snapshot(
                    ProxyEnabledKey to true,
                    ProxyHostKey to "proxy.example",
                    ProxyPortKey to 3128,
                    ProxyUsernameKey to "user",
                    ProxyPasswordKey to "secret",
                ),
            ),
        )
        assertFalse(
            owner.savedRouteChanged(
                snapshot(
                    ProxyEnabledKey to true,
                    ProxyHostKey to "proxy.example",
                    ProxyPortKey to 3128,
                    StreamBypassProxyKey to true,
                ),
            ),
        )
        assertTrue(owner.savedRouteChanged(enabled(host = "other.example")))
        assertTrue(owner.savedRouteChanged(enabled(port = 8080)))
        assertTrue(owner.savedRouteChanged(snapshot(ProxyEnabledKey to false)))
    }

    @Test
    fun theDiagnosticNamesTheRouteWithoutItsCredentials() {
        val owner = IosNetworkOwner(
            {
                snapshot(
                    ProxyEnabledKey to true,
                    ProxyHostKey to "proxy.example",
                    ProxyPortKey to 3128,
                    ProxyUsernameKey to "user",
                    ProxyPasswordKey to "secret",
                )
            },
        ) {}
        val line = owner.describe()
        assertTrue("proxy.example:3128" in line)
        assertFalse("user" in line)
        assertFalse("secret" in line)
    }

    @Test
    fun aDisabledSwitchLeavesTheRunningSessionOnTheSystemRoute() {
        val owner = IosNetworkOwner({ snapshot(ProxyEnabledKey to false) }) {}
        assertNull(owner.runningRoute)
        assertEquals("network: system route", owner.describe())
    }

    @Test
    fun aStreamFollowsTheSavedRouteUnlessItIsBypassed() {
        try {
            IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = false)
            assertEquals("http://proxy.example:3128", IosStreamProxyRouting.routeForStream()?.endpointKey)
            IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = true)
            assertNull(IosStreamProxyRouting.routeForStream())
            assertFalse(IosStreamProxyRouting.mustProxy())
        } finally {
            IosStreamProxyRouting.updateStreamRoute(null, bypass = false)
        }
    }

    @Test
    fun aBypassedStreamKeepsTheSystemRouteEvenWhileRotating() {
        val rotation = IosProxyRotation()
        rotation.loadRoutes(listOf(httpRoute(host = "pool.example", port = 8080)))
        try {
            IosStreamProxyRouting.updateRotation(rotation)
            // A bypass saved while the static proxy was off still rules the stream route.
            IosStreamProxyRouting.updateStreamRoute(null, bypass = true)
            assertNull(IosStreamProxyRouting.routeForStream())
            assertFalse(IosStreamProxyRouting.mustProxy())
            IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = false)
            assertEquals("http://pool.example:8080", IosStreamProxyRouting.routeForStream()?.endpointKey)
        } finally {
            IosStreamProxyRouting.updateRotation(null)
            IosStreamProxyRouting.updateStreamRoute(null, bypass = false)
        }
    }

    @Test
    fun aRotationWithNoMemberAndNoSavedRouteMustNotLeakDirect() {
        val rotation = IosProxyRotation()
        try {
            IosStreamProxyRouting.updateRotation(rotation)
            assertTrue(IosStreamProxyRouting.streamBlocked())
            assertTrue(IosStreamProxyRouting.mustProxy())
            IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = false)
            assertFalse(IosStreamProxyRouting.streamBlocked())
            assertEquals("http://proxy.example:3128", IosStreamProxyRouting.routeForStream()?.endpointKey)
        } finally {
            IosStreamProxyRouting.updateRotation(null)
            IosStreamProxyRouting.updateStreamRoute(null, bypass = false)
        }
    }

    @Test
    fun thePoolRotatesAndCoolsAMemberItsConnectionBrokeThrough() {
        val rotation = IosProxyRotation()
        val first = httpRoute(host = "one.example", port = 1001)
        val second = httpRoute(host = "two.example", port = 1002)
        rotation.loadRoutes(listOf(first, second))
        assertEquals("http://one.example:1001", rotation.next()?.endpointKey)
        assertEquals("http://two.example:1002", rotation.next()?.endpointKey)
        rotation.markFailed(first)
        assertEquals("http://two.example:1002", rotation.next()?.endpointKey)
        assertEquals(1, rotation.activeCount())
        rotation.clear()
        assertNull(rotation.next())
        assertEquals(0, rotation.activeCount())
    }

    @Test
    fun aStarvedRotationRefusesTheOpenAndDefersToASavedRoute() {
        val rotation = IosProxyRotation()
        try {
            IosStreamProxyRouting.updateRotation(rotation)
            // Rotation on, nothing to route through, no static route: the open must fail, not leak direct.
            assertFailsWith<ByteSourceException> {
                IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)
            }
            // A configured static route is the acceptable fallback: the same starved pool defers to it.
            IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = false)
            assertFalse(IosStreamProxyRouting.streamBlocked())
            assertEquals(
                "http://proxy.example:3128",
                IosStreamProxyRouting
                    .applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)
                    ?.endpointKey,
            )
        } finally {
            IosStreamProxyRouting.updateRotation(null)
            IosStreamProxyRouting.updateStreamRoute(null, bypass = false)
        }
    }

    @Test
    fun anEmptyRefreshKeepsCooledMembersAndThePoolStillAnswers() {
        val rotation = IosProxyRotation()
        val first = httpRoute(host = "one.example", port = 1001)
        val second = httpRoute(host = "two.example", port = 1002)
        rotation.loadRoutes(listOf(first, second))
        rotation.markFailed(first)
        rotation.markFailed(second)
        // Every member sits in its failure cooldown: nothing counts live, but the pool is not empty.
        assertEquals(0, rotation.activeCount())
        assertTrue(rotation.hasMembers())
        // The commit an empty refresh runs keeps the pool it was meant to replace; a failed refresh
        // never reaches the commit at all, so its members survive the same way.
        rotation.replaceRoutes(emptyList())
        assertTrue(rotation.hasMembers())
        try {
            IosStreamProxyRouting.updateRotation(rotation)
            assertTrue(IosStreamProxyRouting.mustProxy())
            assertFalse(IosStreamProxyRouting.streamBlocked())
            val routed =
                assertNotNull(
                    IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration),
                )
            assertTrue(routed.endpointKey in setOf(first.endpointKey, second.endpointKey))
        } finally {
            IosStreamProxyRouting.updateRotation(null)
        }
    }
}
