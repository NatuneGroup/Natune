package moe.rukamori.archivetune.desktop.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.playback.*
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode

class DesktopTransportStateTest {
    @Test fun loopStatusRoundTripsTheMprisVocabulary() {
        QueueRepeatMode.entries.forEach { mode ->
            assertEquals(mode, mprisLoopStatusRepeat(mode.mprisLoopStatus()))
        }
        assertEquals("None", QueueRepeatMode.OFF.mprisLoopStatus())
        assertEquals("Track", QueueRepeatMode.ONE.mprisLoopStatus())
        assertEquals("Playlist", QueueRepeatMode.ALL.mprisLoopStatus())
        assertNull(mprisLoopStatusRepeat("playlist"))
        assertNull(mprisLoopStatusRepeat("Repeat"))
        assertNull(mprisLoopStatusRepeat(""))
    }

    @Test fun mprisVolumeClampsToTheEngineRange() {
        assertEquals(0.5f, mprisVolumeLevel(0.5))
        assertEquals(0f, mprisVolumeLevel(-0.25))
        assertEquals(1f, mprisVolumeLevel(1.5))
        assertNull(mprisVolumeLevel(Double.NaN))
        assertNull(mprisVolumeLevel(Double.POSITIVE_INFINITY))
    }

    @Test fun queueAndVolumeWritesReachTheOwnersNotTheController() = runBlocking {
        val commands = mutableListOf<PlaybackCommand>()
        val port = object : PlaybackPort {
            override val snapshots = MutableStateFlow<PlaybackSnapshot?>(DesktopMediaControlsTest.sample())
            override val failures = MutableSharedFlow<PlaybackFailure>()
            override fun submit(command: PlaybackCommand) { commands += command }
            override fun close() {}
        }
        val collector = CoroutineScope(coroutineContext + Job())
        val controller = MusicPlayerController(port, collector)
        var shuffle: Boolean? = null
        var repeat: QueueRepeatMode? = null
        var volume: Float? = null
        val controls = DesktopMediaControls({ controller.snapshot.value }, { it() }) {
            controller.consumeDesktopTransport(it, {}, {}, { shuffle = it }, { repeat = it }, { volume = it })
        }
        controls.submit(DesktopTransportAction.SetShuffle(true))
        controls.submit(DesktopTransportAction.SetRepeat(QueueRepeatMode.ONE))
        controls.submit(DesktopTransportAction.SetVolume(0.4f))
        controls.submit(DesktopTransportAction.SetVolume(Float.NaN))
        assertEquals(true, shuffle)
        assertEquals(QueueRepeatMode.ONE, repeat)
        assertEquals(0.4f, volume)
        assertTrue(commands.isEmpty(), "Shuffle, repeat and volume are not playback port commands")
        collector.cancel()
    }
}
