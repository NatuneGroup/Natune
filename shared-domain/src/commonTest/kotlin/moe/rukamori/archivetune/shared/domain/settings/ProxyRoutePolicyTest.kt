package moe.rukamori.archivetune.shared.domain.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProxyRoutePolicyTest {
    private fun snapshot(vararg entries: Pair<PreferenceKey<*>, Any>): PreferenceSnapshot =
        PreferenceSnapshot.of(*entries)

    @Test
    fun disabledSwitchReadsNoRoute() {
        val preferences = snapshot(
            ProxyEnabledKey to false,
            ProxyHostKey to "proxy.example",
            ProxyPortKey to 3128,
        )
        assertNull(preferences.proxyRoute())
    }

    @Test
    fun enabledRouteUsesTheSavedHostAndPort() {
        val preferences = snapshot(
            ProxyEnabledKey to true,
            ProxyHostKey to "proxy.example",
            ProxyPortKey to 3128,
            ProxyTypeKey to "HTTP",
        )
        val route = requireNotNull(preferences.proxyRoute())
        assertEquals(ProxyRouteType.HTTP, route.type)
        assertEquals("proxy.example", route.host)
        assertEquals(3128, route.port)
        assertFalse(route.hasCredentials)
        assertEquals("http://proxy.example:3128", route.endpointKey)
    }

    @Test
    fun aPortInsideTheHostWinsOverThePortRow() {
        val preferences = snapshot(
            ProxyEnabledKey to true,
            ProxyHostKey to "http://proxy.example:8081/",
            ProxyPortKey to 3128,
        )
        assertEquals(8081, requireNotNull(preferences.proxyRoute()).port)
    }

    @Test
    fun aMissingPortFallsBackToTheStoredOne() {
        val preferences = snapshot(ProxyEnabledKey to true, ProxyHostKey to "proxy.example", ProxyPortKey to 1080)
        assertEquals(1080, requireNotNull(preferences.proxyRoute()).port)
    }

    @Test
    fun socksAndCredentialsAreReadAsStored() {
        val preferences = snapshot(
            ProxyEnabledKey to true,
            ProxyHostKey to "socks.example",
            ProxyPortKey to 1080,
            ProxyTypeKey to "SOCKS",
            ProxyUsernameKey to "  user  ",
            ProxyPasswordKey to "secret",
            StreamBypassProxyKey to true,
        )
        val route = requireNotNull(preferences.proxyRoute())
        assertEquals(ProxyRouteType.SOCKS, route.type)
        assertEquals("user", route.username)
        assertTrue(route.hasCredentials)
        assertTrue(route.bypassStreams)
        assertEquals("socks://socks.example:1080", route.endpointKey)
    }

    @Test
    fun aHalfConfiguredCredentialPairCountsAsNoCredentials() {
        val preferences = snapshot(
            ProxyEnabledKey to true,
            ProxyHostKey to "proxy.example",
            ProxyPortKey to 8080,
            ProxyUsernameKey to "user",
        )
        assertFalse(requireNotNull(preferences.proxyRoute()).hasCredentials)
    }

    @Test
    fun unusableHostOrPortReadsNoRoute() {
        assertNull(snapshot(ProxyEnabledKey to true, ProxyHostKey to "", ProxyPortKey to 8080).proxyRoute())
        assertNull(snapshot(ProxyEnabledKey to true, ProxyHostKey to "bad host", ProxyPortKey to 8080).proxyRoute())
        assertNull(snapshot(ProxyEnabledKey to true, ProxyHostKey to "proxy.example", ProxyPortKey to 0).proxyRoute())
        assertNull(snapshot(ProxyEnabledKey to true, ProxyHostKey to "proxy.example", ProxyPortKey to 70000).proxyRoute())
    }

    @Test
    fun theRouteDescriptionNeverCarriesCredentials() {
        val route = ProxyRoute(ProxyRouteType.HTTP, "proxy.example", 8080, "user", "secret")
        assertFalse("user" in route.toString())
        assertFalse("secret" in route.toString())
        assertFalse("user" in route.endpointKey)
    }
}
