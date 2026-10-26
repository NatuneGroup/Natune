/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*
import moe.rukamori.archivetune.shared.domain.playback.*

class DesktopMediaControlsOwnerTest {
    @Test fun nativeActionsReachSameControllerWithoutOwningQueueEngineOrFailureStream() = runBlocking {
        val calls = mutableListOf<PlaybackCommand>()
        val port = object : PlaybackPort {
            override val snapshots = MutableStateFlow<PlaybackSnapshot?>(DesktopMediaControlsTest.sample())
            override val failures = MutableSharedFlow<PlaybackFailure>()
            override fun submit(command: PlaybackCommand) { calls += command }
            override fun close() { error("Desktop integration must not close borrowed player") }
        }
        val collector = CoroutineScope(coroutineContext + Job())
        val controller = MusicPlayerController(port, collector)
        yield()
        var next = 0
        var previous = 0
        val controls = DesktopMediaControls({ controller.snapshot.value }, { it() }) {
            controller.consumeDesktopTransport(it, { next++ }, { previous++ })
        }
        controls.submit(DesktopTransportAction.Play)
        controls.submit(DesktopTransportAction.Pause)
        controls.submit(DesktopTransportAction.Next)
        controls.submit(DesktopTransportAction.Previous)
        controls.setPosition(trackPath("first"), 123_000)
        controls.submit(DesktopTransportAction.Speed(1.5f))
        controls.submit(DesktopTransportAction.Speed(Float.NaN))
        assertEquals(listOf<PlaybackCommand>(
            PlaybackCommand.Play, PlaybackCommand.Pause, PlaybackCommand.SeekTo(123), PlaybackCommand.SetSpeed(1.5f),
        ), calls)
        assertEquals(1, next); assertEquals(1, previous)
        assertSame(port.failures, controller.failures)
        collector.cancel()
    }
}
