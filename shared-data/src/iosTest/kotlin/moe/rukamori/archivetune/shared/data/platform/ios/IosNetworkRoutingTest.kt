package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
}
