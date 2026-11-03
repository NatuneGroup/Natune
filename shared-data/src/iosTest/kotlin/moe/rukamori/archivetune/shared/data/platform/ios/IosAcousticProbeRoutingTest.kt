package moe.rukamori.archivetune.shared.data.platform.ios

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticWindow
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator
import moe.rukamori.archivetune.shared.domain.settings.ProxyRoute
import moe.rukamori.archivetune.shared.domain.settings.ProxyRouteType

class IosAcousticProbeRoutingTest {
    private val remote = PlaybackLocator.Remote("https://media.example/track.m4a")
    private val local = PlaybackLocator.LocalFile("/var/mobile/Containers/Data/song.flac")

    private fun httpRoute() = ProxyRoute(ProxyRouteType.HTTP, "proxy.example", 3128, null, null)

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
    fun aStaticProxyRefusesARemoteProbeBeforeAnyOpen() {
        IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = false)
        assertEquals(PROXIED_REMOTE_REFUSAL, iosProbeRefusalOf(remote))
        val window = runBlocking { IosAcousticProbe.window(remote) }
        assertEquals(PROXIED_REMOTE_REFUSAL, assertIs<AcousticWindow.Refused>(window).code)
    }

    @Test
    fun anEnabledRotationWithAnEmptyPoolRefusesARemoteProbe() {
        IosStreamProxyRouting.updateRotation(IosProxyRotation())
        assertEquals(PROXIED_REMOTE_REFUSAL, iosProbeRefusalOf(remote))
        val window = runBlocking { IosAcousticProbe.window(remote) }
        assertEquals(PROXIED_REMOTE_REFUSAL, assertIs<AcousticWindow.Refused>(window).code)
    }

    @Test
    fun aLocalPathStaysAuditableWhileStreamsAreProxied() {
        IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = false)
        assertNull(iosProbeRefusalOf(local))
        assertEquals(local.path, iosProbeSourceOf(local))
        IosStreamProxyRouting.updateStreamRoute(null, bypass = false)
        IosStreamProxyRouting.updateRotation(IosProxyRotation())
        assertNull(iosProbeRefusalOf(local))
        assertEquals(local.path, iosProbeSourceOf(local))
    }

    @Test
    fun aRemoteProbeKeepsItsDirectOpenWhenNothingIsProxied() {
        assertNull(iosProbeRefusalOf(remote))
        assertEquals(remote.url, iosProbeSourceOf(remote))
        // A saved bypass sends playback direct as well, so the audit may follow it.
        IosStreamProxyRouting.updateStreamRoute(httpRoute(), bypass = true)
        assertNull(iosProbeRefusalOf(remote))
    }
}
