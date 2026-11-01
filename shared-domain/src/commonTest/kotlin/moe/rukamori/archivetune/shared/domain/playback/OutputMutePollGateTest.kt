package moe.rukamori.archivetune.shared.domain.playback

import kotlin.test.Test
import kotlin.test.assertEquals

class OutputMutePollGateTest {

    @Test
    fun playingSessionAlwaysPolls() {
        assertEquals(true, shouldPollOutputMute(playWhenReady = true, isEnded = false, mutePausePending = false))
    }

    @Test
    fun manualPauseAndMediaEndStopPolling() {
        assertEquals(false, shouldPollOutputMute(playWhenReady = false, isEnded = false, mutePausePending = false))
        assertEquals(false, shouldPollOutputMute(playWhenReady = true, isEnded = true, mutePausePending = false))
    }

    @Test
    fun pendingMutePauseKeepsPollingUntilUnmute() {
        assertEquals(true, shouldPollOutputMute(playWhenReady = false, isEnded = false, mutePausePending = true))
        assertEquals(true, shouldPollOutputMute(playWhenReady = true, isEnded = false, mutePausePending = true))
    }
}
