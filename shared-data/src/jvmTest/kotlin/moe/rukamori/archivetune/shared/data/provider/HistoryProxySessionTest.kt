package moe.rukamori.archivetune.shared.data.provider

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import moe.rukamori.archivetune.shared.domain.repository.ContentAccountChanged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class HistoryProxySessionTest {
    @Test fun everyPageAndRetryKeepsTheFirstExitWithoutSelectingTheRotatingPoolAgain() {
        var selections = 0
        val selector = object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> {
                assertEquals(URI("https://music.youtube.com"), uri)
                selections++
                return listOf(proxy("exit-$selections.example"))
            }
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
        }
        val session = HistoryProxySession()
        fun read(continuation: String?) = session.routeFor(continuation, "account", "channel") {
            selectHistoryProxyRoute(selector, null, null, null)
        }
        val first = read(null)
        session.acceptNext("older")
        assertSame(first, read("older"))
        assertSame(first, read("older"))
        session.acceptNext("oldest")
        assertSame(first, read("oldest"))
        assertEquals(1, selections)
    }

    @Test fun aRefreshChoosesAgainAndRejectsTheOldSessionsCursor() {
        val session = HistoryProxySession()
        val first = route("first.example")
        val second = route("second.example")
        assertSame(first, session.routeFor(null, "account", null) { first })
        session.acceptNext("old-cursor")
        assertSame(second, session.routeFor(null, "account", null) { second })
        session.acceptNext("new-cursor")
        assertFailsWith<YouTubeHistoryRoutingUnavailable> {
            session.routeFor("old-cursor", "account", null) { error("must not select") }
        }
        assertSame(second, session.routeFor("new-cursor", "account", null) { error("must not select") })
    }

    @Test fun anAccountOrBrandChannelSwitchClearsThePinBeforeItCanSendAContinuation() {
        for ((cookie, channel) in listOf("another-account" to "channel", "account" to "another-channel")) {
            val session = HistoryProxySession()
            session.routeFor(null, "account", "channel") { route("first.example") }
            session.acceptNext("older")
            assertFailsWith<ContentAccountChanged> {
                session.routeFor("older", cookie, channel) { error("must not select for an old cursor") }
            }
            assertFailsWith<ContentAccountChanged> {
                session.routeFor("older", "account", "channel") { error("old pin was cleared") }
            }
            val replacement = route("replacement.example")
            assertSame(replacement, session.routeFor(null, cookie, channel) { replacement })
        }
    }

    @Test fun explicitResetDropsTheRouteAndCursor() {
        val session = HistoryProxySession()
        session.routeFor(null, "account", null) { route("first.example") }
        session.acceptNext("older")
        session.reset()
        assertFailsWith<ContentAccountChanged> {
            session.routeFor("older", "account", null) { error("must not select") }
        }
    }

    @Test fun separateHistoryConsumersKeepTheirOwnPinWhileSharingTheSerializedClient() {
        val catalogue = HistoryProxySession()
        val accountListing = HistoryProxySession()
        val catalogueRoute = route("catalogue.example")
        val listingRoute = route("listing.example")
        catalogue.routeFor(null, "account", null) { catalogueRoute }
        accountListing.routeFor(null, "account", null) { listingRoute }
        catalogue.acceptNext("catalogue-older")
        accountListing.acceptNext("listing-older")
        assertSame(catalogueRoute, catalogue.routeFor("catalogue-older", "account", null) { error("must not select") })
        assertSame(listingRoute, accountListing.routeFor("listing-older", "account", null) { error("must not select") })
    }

    @Test fun directRoutingIsPinnedExplicitlyAndPublicProxiesNeverReceiveStaticCredentials() {
        val configured = proxy("personal.example")
        val personal = selectHistoryProxyRoute(fixed(configured), configured, "user", "fixture password")
        assertEquals("user", personal.username)
        assertEquals("fixture password", personal.password)
        val rotating = selectHistoryProxyRoute(fixed(proxy("public.example")), configured, "user", "fixture password")
        assertNull(rotating.username)
        assertNull(rotating.password)
        val direct = selectHistoryProxyRoute(fixed(Proxy.NO_PROXY), configured, "user", "fixture password")
        assertSame(Proxy.NO_PROXY, direct.proxy)
        assertNull(direct.username)
        assertNull(direct.password)
    }

    private fun route(host: String) = HistoryProxyRoute(proxy(host), null, null)
    private fun proxy(host: String) = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, 8080))
    private fun fixed(proxy: Proxy) = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy)
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {}
    }
}
