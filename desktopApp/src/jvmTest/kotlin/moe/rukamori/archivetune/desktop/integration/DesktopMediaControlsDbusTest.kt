/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Assume.assumeTrue
import moe.rukamori.archivetune.desktop.integration.linux.*
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant

class DesktopMediaControlsDbusTest {
    @Test fun liveSessionBusExportsMetadataCommandsOccurrenceGuardSeekedAndTrayMenu() {
        assumeTrue("Needs dbus-run-session", System.getenv("DBUS_SESSION_BUS_ADDRESS") != null)
        val actions = LinkedBlockingQueue<DesktopTransportAction>()
        val seeks = LinkedBlockingQueue<Long>()
        var snapshot = DesktopMediaControlsTest.sample()
        val controls = DesktopMediaControls({ snapshot }, { it() }, actions::add)
        LinuxSystemIntegration(controls, {}, {}, {}).use { integration ->
            DBusConnectionBuilder.forSessionBus().withShared(false).build().use { client ->
                val player = client.getRemoteObject(integration.busName, MPRIS_PATH, MprisPlayer::class.java)
                val properties = client.getRemoteObject(integration.busName, MPRIS_PATH, Properties::class.java)
                client.addSigHandler(MprisPlayer.Seeked::class.java) { seeks += it.position }.use {
                    integration.update(snapshot, DesktopMediaMetadata("first", "Title", listOf("Artist"), canNext = true))
                    assertEquals("Paused", properties.GetAll(MPRIS_PLAYER).getValue("PlaybackStatus").value)
                    val rate: Any = properties.Get(MPRIS_PLAYER, "Rate")
                    assertEquals(1.0, rate)
                    val metadata = properties.GetAll(MPRIS_PLAYER).getValue("Metadata").value as Map<*, *>
                    assertEquals("Title", (metadata["xesam:title"] as Variant<*>).value)
                    player.Next()
                    assertEquals(DesktopTransportAction.Next, actions.poll(2, TimeUnit.SECONDS))
                    player.SetPosition(DBusPath(trackPath("other-occurrence")), 500_000)
                    player.SetPosition(DBusPath(trackPath("first")), 500_000)
                    assertEquals(DesktopTransportAction.SeekTo("first", 500), actions.poll(2, TimeUnit.SECONDS))
                    assertTrue(actions.isEmpty())
                    properties.Set(MPRIS_PLAYER, "Rate", Variant(1.5))
                    assertEquals(DesktopTransportAction.Speed(1.5f), actions.poll(2, TimeUnit.SECONDS))
                    snapshot = snapshot.copy(positionMs = 500, discontinuityGeneration = 1)
                    integration.update(snapshot, null)
                    assertEquals(500_000, seeks.poll(2, TimeUnit.SECONDS))
                    val tray = client.getRemoteObject(integration.busName, SNI_PATH, Properties::class.java)
                    assertEquals("/Menu", (tray.GetAll(SNI_INTERFACE).getValue("Menu").value as DBusPath).path)
                    assertFalse(integration.trayAvailable, "No real watcher/host in the isolated bus")
                    val menu = client.getRemoteObject(integration.busName, "/Menu", DbusMenu::class.java)
                    assertEquals(5, menu.GetLayout(0, -1, emptyList()).layout.children.size)
                    menu.Event(2, "clicked", Variant(0), org.freedesktop.dbus.types.UInt32(0))
                    assertEquals(DesktopTransportAction.Toggle, actions.poll(2, TimeUnit.SECONDS))
                }
            }
        }
    }
}
