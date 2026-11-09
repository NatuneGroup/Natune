package moe.rukamori.archivetune.playback

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.UnknownHostException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutoOfflineAvailabilityTest {
    @Test
    fun aSourceConnectionFailurePrecedesTheNetworkCallbackAndIsNotClearedByCachedPlayback() {
        var connected = true
        val availability = AutoOfflineAvailability { connected }
        assertFalse(availability.isOffline())
        assertTrue(availability.onConnectionFailure())
        assertTrue(availability.isOffline())
        assertFalse(availability.onConnectionFailure())
        connected = false
        assertFalse(availability.onConnectivityChanged(false))
        assertTrue(availability.isOffline())
        connected = true
        assertTrue(availability.onConnectivityChanged(true))
        assertFalse(availability.isOffline())
        assertFalse(availability.onConnectivityChanged(true))
    }

    @Test
    fun realNetworkLossChangesAvailabilityOnceAndAnUnvalidatedLinkStaysOffline() {
        var connected = true
        val availability = AutoOfflineAvailability { connected }
        connected = false
        assertTrue(availability.onConnectivityChanged(false))
        assertFalse(availability.onConnectivityChanged(false))
        assertTrue(availability.isOffline())
        assertFalse(availability.onConnectivityChanged(true)) // The synchronous capability check still fails.
        assertTrue(availability.isOffline())
    }

    @Test
    fun wrappedConnectionErrorsAreRecognizedButLoginHttpAndDecoderErrorsAreNotNetworkLoss() {
        assertTrue(IOException("resolver", UnknownHostException()).isNetworkPlaybackFailure())
        assertTrue(PlaybackException("timeout", null, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT).isNetworkPlaybackFailure())
        assertTrue(PlaybackException("wrapped", IOException("tier", UnknownHostException()),
            PlaybackException.ERROR_CODE_REMOTE_ERROR).isNetworkPlaybackFailure())
        assertFalse(PlaybackException("login", null, PlaybackException.ERROR_CODE_REMOTE_ERROR).isNetworkPlaybackFailure())
        assertFalse(PlaybackException("http", null, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS).isNetworkPlaybackFailure())
        assertFalse(PlaybackException("decoder", null, PlaybackException.ERROR_CODE_DECODING_FAILED).isNetworkPlaybackFailure())
    }

    @Test
    fun causeInspectionIsBoundedEvenForAnExceptionCycle() {
        val first = IOException("one")
        val second = IOException("two", first)
        first.initCause(second)
        assertFalse(first.isNetworkPlaybackFailure())
    }
}
