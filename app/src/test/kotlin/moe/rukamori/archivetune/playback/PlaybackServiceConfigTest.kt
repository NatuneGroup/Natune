package moe.rukamori.archivetune.playback

import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackServiceConfigTest {
    @Test
    fun unpluggingTheOutputAlwaysPausesPlayback() {
        // Headphones becoming noisy must pause rather than blast the speaker, on both players.
        assertTrue(PlaybackServiceConfig.HANDLE_AUDIO_BECOMING_NOISY)
    }
}
