package moe.rukamori.archivetune.desktop.integration

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackCommand
import moe.rukamori.archivetune.shared.domain.playback.PlaybackFailure
import moe.rukamori.archivetune.shared.domain.playback.PlaybackPort
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode

class DesktopGuestTransportLockTest {
    @Test fun guestRefusesQueueAndPositionButKeepsPlayback() = runBlocking {
        val commands = mutableListOf<PlaybackCommand>()
        val port = object : PlaybackPort {
            override val snapshots = MutableStateFlow<PlaybackSnapshot?>(DesktopMediaControlsTest.sample())
            override val failures = MutableSharedFlow<PlaybackFailure>()
            override fun submit(command: PlaybackCommand) { commands += command }
            override fun close() {}
        }
        val collector = CoroutineScope(coroutineContext + Job())
        val controller = MusicPlayerController(port, collector)
        val consumed = mutableListOf<DesktopTransportAction>()
        var guest = true
        // The same gate the host services apply: while the user is a room guest, guestAllowed() decides.
        val controls = DesktopMediaControls({ controller.snapshot.value }, { it() }) { action ->
            if (!guest || action.guestAllowed()) consumed += action
        }
        controls.submit(DesktopTransportAction.Next)
        controls.submit(DesktopTransportAction.Previous)
        controls.submit(DesktopTransportAction.SeekTo("first", 1000))
        controls.setPosition(trackPath("first"), 5_000_000)
        controls.seek(2_000_000)
        controls.submit(DesktopTransportAction.Toggle)
        controls.submit(DesktopTransportAction.SetVolume(0.5f))
        assertEquals(
            listOf<DesktopTransportAction>(DesktopTransportAction.Toggle, DesktopTransportAction.SetVolume(0.5f)),
            consumed,
            "a room guest keeps play/pause and volume only",
        )
        guest = false
        controls.submit(DesktopTransportAction.Next)
        assertEquals(3, consumed.size)
        collector.cancel()
    }

    @Test fun smtcRepeatModesMatchTheStoredPreference() {
        assertEquals(QueueRepeatMode.OFF, nativeRepeatMode(0))
        assertEquals(QueueRepeatMode.ONE, nativeRepeatMode(1))
        assertEquals(QueueRepeatMode.ALL, nativeRepeatMode(2))
        assertEquals(QueueRepeatMode.OFF, nativeRepeatMode(3))
        assertEquals(QueueRepeatMode.OFF, nativeRepeatMode(-1))
    }
}
