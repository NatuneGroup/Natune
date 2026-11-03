package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.data.bytesource.ByteSourceException
import moe.rukamori.archivetune.shared.domain.playback.PlaybackErrorKind
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailureReason
import moe.rukamori.archivetune.shared.domain.playback.STREAM_BLOCKED_MESSAGE
import moe.rukamori.archivetune.shared.domain.playback.toPlaybackErrorInfo
import moe.rukamori.archivetune.shared.domain.settings.IpRotationEnabledKey
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

    /** Stream routing is process state, so every test starts and ends with it empty. */
    @BeforeTest
    fun resetStreamRouting() {
        IosStreamProxyRouting.updateRotation(null)
        IosStreamProxyRouting.updateStreamRoute(null, bypass = false)
    }

    @AfterTest
    fun clearStreamRouting() {
        IosStreamProxyRouting.updateRotation(null)
        IosStreamProxyRouting.updateStreamRoute(null, bypass = false)
    }

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

    @Test
    fun anOwnerBuiltWithRotationSavedOnBlocksStreamsBeforeThePoolFills() {
        val owner = IosNetworkOwner({ snapshot(IpRotationEnabledKey to true) }) {}
        assertTrue(IosStreamProxyRouting.streamBlocked())
        assertTrue(IosStreamProxyRouting.mustProxy())
        assertTrue(owner.rotationBlocked.value)
        assertFailsWith<ByteSourceException> {
            IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)
        }
    }

    @Test
    fun anEnableBlocksStreamsForTheWholeFetch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<List<ProxyRoute>>()
        val rotation = IosProxyRotation { _ -> started.complete(Unit); release.await() }
        val owner = IosNetworkOwner({ snapshot() }, {}, rotation)
        val pending = async { owner.applyIpRotation(enabled = true, refresh = false) }
        started.await()
        // The toggle is already committed: while the fetch runs, the installed empty pool must refuse opens.
        assertTrue(IosStreamProxyRouting.streamBlocked())
        assertFailsWith<ByteSourceException> {
            IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)
        }
        release.complete(listOf(httpRoute(host = "pool.example", port = 8080)))
        assertEquals(1, pending.await())
        assertFalse(IosStreamProxyRouting.streamBlocked())
        assertEquals(
            "http://pool.example:8080",
            IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)?.endpointKey,
        )
    }

    @Test
    fun aCancelledEnableLeavesTheInstalledRotationBlocking() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val rotation = IosProxyRotation { _ -> started.complete(Unit); awaitCancellation() }
        val owner = IosNetworkOwner({ snapshot() }, {}, rotation)
        val job = launch { runCatching { owner.applyIpRotation(enabled = true, refresh = false) } }
        started.await()
        job.cancel()
        job.join()
        assertTrue(IosStreamProxyRouting.streamBlocked())
        assertTrue(IosStreamProxyRouting.mustProxy())
        assertFailsWith<ByteSourceException> {
            IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)
        }
    }

    @Test
    fun aFailedOrEmptyRefreshKeepsCooledMembersInstalledAndAnswering() = runBlocking {
        val first = httpRoute(host = "one.example", port = 1001)
        val second = httpRoute(host = "two.example", port = 1002)
        var fetches = 0
        val rotation = IosProxyRotation { _ ->
            fetches++
            if (fetches == 1) emptyList() else throw IllegalStateException("list fetch failed")
        }
        rotation.loadRoutes(listOf(first, second))
        rotation.markFailed(first)
        rotation.markFailed(second)
        val owner = IosNetworkOwner({ snapshot(IpRotationEnabledKey to true) }, {}, rotation)
        // An empty refresh keeps the cooled pool installed — opens still route through a cooled member.
        assertEquals(0, owner.applyIpRotation(enabled = true, refresh = true))
        assertTrue(rotation.hasMembers())
        assertTrue(IosStreamProxyRouting.mustProxy())
        assertFalse(IosStreamProxyRouting.streamBlocked())
        val routed =
            assertNotNull(
                IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration),
            )
        assertTrue(routed.endpointKey in setOf(first.endpointKey, second.endpointKey))
        // A fetch that throws leaves the same install in place: the members it already held still answer.
        try {
            owner.applyIpRotation(enabled = true, refresh = true)
            fail("a failed fetch should propagate")
        } catch (expected: IllegalStateException) {
        }
        assertTrue(IosStreamProxyRouting.mustProxy())
        assertNotNull(IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration))
    }

    @Test
    fun anExplicitDisableIsTheOnlyWayBackToDirect() = runBlocking {
        val rotation = IosProxyRotation { _ -> emptyList() }
        val owner = IosNetworkOwner({ snapshot(IpRotationEnabledKey to true) }, {}, rotation)
        assertTrue(IosStreamProxyRouting.streamBlocked())
        assertEquals(0, owner.applyIpRotation(enabled = true, refresh = false))
        // The empty answer still leaves the rotation installed — only an explicit disable takes it out.
        assertTrue(IosStreamProxyRouting.streamBlocked())
        assertEquals(0, owner.applyIpRotation(enabled = false, refresh = false))
        assertFalse(IosStreamProxyRouting.mustProxy())
        assertFalse(owner.rotationBlocked.value)
        assertNull(IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration))
        // A saved static route still claims streams after rotation leaves — disable never touches it.
        IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = false)
        assertEquals(
            "http://proxy.example:3128",
            IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)?.endpointKey,
        )
    }

    @Test
    fun aBlockedOpenReportsTheSanitizedRotationStatus() {
        IosNetworkOwner({ snapshot(IpRotationEnabledKey to true) }) {}
        val failure = assertFailsWith<ByteSourceException> {
            IosStreamProxyRouting.applyTo(NSURLSessionConfiguration.ephemeralSessionConfiguration)
        }
        // The status is a fixed token — no proxy host, port or credential reaches the error dialog.
        assertEquals(STREAM_BLOCKED_MESSAGE, failure.message)
        assertEquals(
            PlaybackErrorKind.STREAM_BLOCKED,
            PlaybackFailure("occ-1", PlaybackFailureReason.OPEN_FAILED, STREAM_BLOCKED_MESSAGE)
                .toPlaybackErrorInfo().kind,
        )
    }

    @Test
    fun theBlockedFlowFlipsBackAfterASuccessfulFetchAndAfterDisable() = runBlocking {
        val route = httpRoute(host = "pool.example", port = 8080)
        var fetches = 0
        val rotation = IosProxyRotation { _ ->
            fetches++
            if (fetches == 1) emptyList() else listOf(route)
        }
        val owner = IosNetworkOwner({ snapshot(IpRotationEnabledKey to true) }, {}, rotation)
        assertTrue(owner.rotationBlocked.value)
        // An empty answer leaves the installed pool blocking: the flow stays on.
        assertEquals(0, owner.applyIpRotation(enabled = true, refresh = true))
        assertTrue(owner.rotationBlocked.value)
        // A fetch that finally answers members unblocks the very next stream.
        assertEquals(1, owner.applyIpRotation(enabled = true, refresh = true))
        assertFalse(owner.rotationBlocked.value)
        // Disable is the explicit clear; the flow reads open only through the saved route afterwards.
        assertEquals(0, owner.applyIpRotation(enabled = false, refresh = false))
        assertFalse(owner.rotationBlocked.value)
        assertFalse(IosStreamProxyRouting.mustProxy())
    }

    @Test
    @OptIn(ExperimentalForeignApi::class)
    fun aLeaseOpenRefusedByTheRotationCarriesTheSanitizedStatus() = memScoped {
        val lease = IosStreamLease(
            object : IosLeaseSource {
                override fun probe(): IosHttpRangeSource.Probe =
                    throw ByteSourceException(STREAM_BLOCKED_MESSAGE)

                override fun readBlocking(position: Long, buffer: ByteArray, offset: Int, length: Int): Int = 0
                override fun interrupt() {}
                override fun close() {}
            },
        )
        lease.engineGeneration = 7
        IosStreamLease.register(7, lease)
        val result = lease.openStream(alloc<LongVar>().ptr, alloc<IntVar>().ptr)
        assertTrue(result != 0)
        // The async error read asks by generation: the current one names the block, a stale one does not.
        assertEquals(STREAM_BLOCKED_MESSAGE, IosStreamLease.refusalOf(7))
        assertNull(IosStreamLease.refusalOf(8))
        IosStreamLease.unregister(7)
        assertNull(IosStreamLease.refusalOf(7))
    }
}
