package moe.rukamori.archivetune.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicWidgetActionsTest {
    @Test
    fun playPausePausesAnActiveTransport() {
        val transport = RecordingTransport(isPlaying = true)

        WidgetTransportCommand.PLAY_PAUSE.applyTo(transport)

        assertEquals(listOf("pause"), transport.calls)
    }

    @Test
    fun playPauseStartsAnIdleTransport() {
        val transport = RecordingTransport(isPlaying = false)

        WidgetTransportCommand.PLAY_PAUSE.applyTo(transport)

        assertEquals(listOf("play"), transport.calls)
    }

    @Test
    fun skipCommandsDriveTheExistingSessionTransport() {
        val next = RecordingTransport(isPlaying = true)
        WidgetTransportCommand.SKIP_NEXT.applyTo(next)
        assertEquals(listOf("seekToNext"), next.calls)

        val previous = RecordingTransport(isPlaying = true)
        WidgetTransportCommand.SKIP_PREVIOUS.applyTo(previous)
        assertEquals(listOf("seekToPrevious"), previous.calls)
    }

    private class RecordingTransport(
        override var isPlaying: Boolean,
    ) : WidgetTransport {
        val calls = mutableListOf<String>()

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekToNext() {
            calls += "seekToNext"
        }

        override fun seekToPrevious() {
            calls += "seekToPrevious"
        }
    }
}
