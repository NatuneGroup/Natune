package moe.rukamori.archivetune.desktop.integration.linux

import kotlin.test.*
import moe.rukamori.archivetune.desktop.integration.DesktopMediaControlsTest
import moe.rukamori.archivetune.desktop.integration.DesktopMediaMetadata
import moe.rukamori.archivetune.desktop.integration.DesktopTransportState
import moe.rukamori.archivetune.desktop.integration.trackPath
import moe.rukamori.archivetune.shared.domain.queue.QueueRepeatMode
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.Variant

class MprisPropertiesTest {
    private fun metadata(artwork: String? = null) = DesktopMediaMetadata(
        "first", "Title", listOf("Artist", "Second"), "Album", artworkUri = artwork,
        canNext = true, canPrevious = true,
    )

    @Suppress("UNCHECKED_CAST")
    private fun trackMap(properties: Map<String, Variant<*>>) =
        properties.getValue("Metadata").value as Map<String, Variant<*>>

    @Test fun playingSnapshotPublishesTrackTransportAndCapabilityProperties() {
        val properties = mprisPlayerProperties(
            DesktopMediaControlsTest.sample().copy(playWhenReady = true, isAudioAdvancing = true),
            metadata("file:///tmp/cover.png"),
            DesktopTransportState(shuffleEnabled = true, repeat = QueueRepeatMode.ALL, volume = 0.6f),
            nowNanos = 0L,
        )
        assertEquals("Playing", properties.getValue("PlaybackStatus").value)
        assertEquals("Playlist", properties.getValue("LoopStatus").value)
        assertEquals(true, properties.getValue("Shuffle").value)
        assertEquals(0.6, properties.getValue("Volume").value as Double, 0.0001)
        assertEquals(1_000_000L, properties.getValue("Position").value)
        assertEquals(true, properties.getValue("CanSeek").value)
        assertEquals(true, properties.getValue("CanPlay").value)
        assertEquals(true, properties.getValue("CanPause").value)
        assertEquals(true, properties.getValue("CanGoNext").value)
        assertEquals(true, properties.getValue("CanGoPrevious").value)
        assertEquals(true, properties.getValue("CanControl").value)
        val track = trackMap(properties)
        assertEquals("Title", track["xesam:title"]?.value)
        assertEquals(listOf("Artist", "Second"), track["xesam:artist"]?.value)
        assertEquals("Album", track["xesam:album"]?.value)
        assertEquals(10_000_000L, track["mpris:length"]?.value)
        assertEquals(trackPath("first"), (track["mpris:trackid"]?.value as DBusPath).path)
        assertEquals("file:///tmp/cover.png", track["mpris:artUrl"]?.value)
    }

    @Test fun remoteArtworkNeverLeavesThePublishedMetadata() {
        val properties = mprisPlayerProperties(
            DesktopMediaControlsTest.sample(),
            metadata("https://artwork.example/cover.png"),
            DesktopTransportState(),
            nowNanos = 0L,
        )
        assertFalse("mpris:artUrl" in trackMap(properties))
    }

    @Test fun stoppedPlayerStillReportsLoopShuffleAndVolume() {
        val properties = mprisPlayerProperties(
            null, null, DesktopTransportState(false, QueueRepeatMode.ONE, 0.3f), nowNanos = 0L,
        )
        assertEquals("Stopped", properties.getValue("PlaybackStatus").value)
        assertEquals("Track", properties.getValue("LoopStatus").value)
        assertEquals(false, properties.getValue("Shuffle").value)
        assertEquals(0.3, properties.getValue("Volume").value as Double, 0.0001)
        assertEquals(0L, properties.getValue("Position").value)
        assertEquals(false, properties.getValue("CanPlay").value)
        assertEquals(false, properties.getValue("CanSeek").value)
        assertTrue(trackMap(properties).isEmpty())
    }
}
