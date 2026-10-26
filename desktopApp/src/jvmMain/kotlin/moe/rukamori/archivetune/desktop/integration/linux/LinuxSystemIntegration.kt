/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.linux

import java.util.concurrent.atomic.AtomicReference
import moe.rukamori.archivetune.desktop.integration.*
import moe.rukamori.archivetune.shared.domain.playback.*
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

/** Real session-bus MPRIS and StatusNotifierItem exports. No bus/host failure can stop audio. */
class LinuxSystemIntegration(
    private val controls: DesktopMediaControls,
    private val raise: () -> Unit,
    private val quit: () -> Unit,
    private val openUri: (String) -> Unit,
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().withShared(false).build(),
) : DesktopSystemIntegration {
    private data class Published(val snapshot: PlaybackSnapshot?, val metadata: DesktopMediaMetadata?)
    private val published = AtomicReference(Published(null, null))
    val busName = "org.mpris.MediaPlayer2.ArchiveTune"
    private val daemon = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
    @Volatile private var registeredWatcher: String? = null
    private val watcherEvents: AutoCloseable
    private val watcherProperties: AutoCloseable
    private val media = Media()
    private val tray = Tray()
    private val menu = Menu()

    init {
        try {
            connection.requestBusName(busName)
            connection.exportObject(MPRIS_PATH, media)
            connection.exportObject(SNI_PATH, tray)
            connection.exportObject("/Menu", menu)
            watcherEvents = connection.addSigHandler(DBus.NameOwnerChanged::class.java) {
                // Re-register after watcher restart; name-owner signals never carry credentials.
                registerTray()
            }
            watcherProperties = connection.addSigHandler(Properties.PropertiesChanged::class.java) {
                if (it.interfaceName == "org.kde.StatusNotifierWatcher" &&
                    it.propertiesChanged["IsStatusNotifierHostRegistered"]?.value == false) raise()
            }
            registerTray()
        } catch (error: Exception) {
            connection.close()
            throw error
        }
    }

    override val trayAvailable: Boolean
        get() = registeredWatcher != null && runCatching {
            daemon.GetNameOwner("org.kde.StatusNotifierWatcher") == registeredWatcher &&
                connection.getRemoteObject("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", Properties::class.java)
                    .Get<Any>("org.kde.StatusNotifierWatcher", "IsStatusNotifierHostRegistered").let {
                        (if (it is Variant<*>) it.value else it) == true
                    }
        }.getOrDefault(false)

    @Synchronized private fun registerTray() {
        try {
            if (!daemon.NameHasOwner("org.kde.StatusNotifierWatcher")) {
                if (registeredWatcher != null) raise()
                registeredWatcher = null; return
            }
            val owner = daemon.GetNameOwner("org.kde.StatusNotifierWatcher")
            if (registeredWatcher == owner) return
            connection.getRemoteObject("org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher", StatusNotifierWatcher::class.java)
                .RegisterStatusNotifierItem(SNI_PATH)
            registeredWatcher = owner
        } catch (_: Exception) { registeredWatcher = null }
    }

    override fun update(snapshot: PlaybackSnapshot?, metadata: DesktopMediaMetadata?) {
        val safe = metadata?.takeIf { it.occurrenceId == snapshot?.occurrenceId }
        val previous = published.getAndSet(Published(snapshot, safe))
        val changed = media.GetAll(MPRIS_PLAYER).filterKeys { it != "Position" }
        val old = playerProperties(previous).filterKeys { it != "Position" }
        val differences = changed.filter { (key, value) -> old[key] != value }
        if (differences.isNotEmpty()) connection.sendMessage(Properties.PropertiesChanged(MPRIS_PATH, MPRIS_PLAYER, differences, emptyList()))
        if (isSeekDiscontinuity(previous.snapshot, snapshot)) {
            connection.sendMessage(MprisPlayer.Seeked(MPRIS_PATH, micros(snapshot!!.positionMs)))
        }
    }

    override fun close() {
        watcherEvents.close()
        watcherProperties.close()
        connection.unExportObject("/Menu")
        connection.unExportObject(SNI_PATH)
        connection.unExportObject(MPRIS_PATH)
        connection.releaseBusName(busName)
        connection.close()
    }

    private fun playerProperties(value: Published): Map<String, Variant<*>> {
        val snapshot = value.snapshot
        val metadata = value.metadata
        val track = linkedMapOf<String, Variant<*>>()
        if (snapshot != null) {
            track["mpris:trackid"] = Variant(DBusPath(trackPath(snapshot.occurrenceId)))
            snapshot.durationMs?.let { track["mpris:length"] = Variant(micros(it)) }
            metadata?.let {
                track["xesam:title"] = Variant(it.title)
                track["xesam:artist"] = Variant(it.artists, "as")
                track["xesam:album"] = Variant(it.album)
                it.artworkUri?.takeIf { art -> art.startsWith("file:") }?.let { art -> track["mpris:artUrl"] = Variant(art) }
            }
        }
        return linkedMapOf(
            "PlaybackStatus" to Variant(snapshot.mprisStatus()),
            "Rate" to Variant(snapshot?.playbackSpeed?.toDouble() ?: 1.0),
            "Metadata" to Variant(track, "a{sv}"),
            "Position" to Variant(snapshot?.let { micros(PlaybackClock.positionMs(it, SystemMonotonicClock.nowNanos())) } ?: 0L),
            "MinimumRate" to Variant(0.25), "MaximumRate" to Variant(4.0),
            "Volume" to Variant(1.0),
            "CanGoNext" to Variant(metadata?.canNext == true),
            "CanGoPrevious" to Variant(metadata?.canPrevious == true),
            "CanPlay" to Variant(snapshot != null && !snapshot.isEnded),
            "CanPause" to Variant(snapshot != null && !snapshot.isEnded),
            "CanSeek" to Variant(snapshot?.isSeekable == true && !snapshot.isEnded),
            "CanControl" to Variant(true),
        )
    }

    inner class Media : MprisRoot, MprisPlayer, Properties {
        override fun getObjectPath() = MPRIS_PATH
        override fun Raise() = raise()
        override fun Quit() = quit()
        override fun Next() { if (published.get().metadata?.canNext == true) controls.submit(DesktopTransportAction.Next) }
        override fun Previous() { if (published.get().metadata?.canPrevious == true) controls.submit(DesktopTransportAction.Previous) }
        override fun Pause() = controls.submit(DesktopTransportAction.Pause)
        override fun PlayPause() = controls.submit(DesktopTransportAction.Toggle)
        override fun Stop() = controls.submit(DesktopTransportAction.Stop)
        override fun Play() = controls.submit(DesktopTransportAction.Play)
        override fun Seek(offset: Long) = controls.seek(offset)
        override fun SetPosition(trackId: DBusPath, position: Long) = controls.setPosition(trackId.path, position)
        override fun OpenUri(uri: String) {
            // The bus delivers this call from any process of the same user, so only the advertised scheme may pass.
            if (!acceptsOpenUri(uri)) throw DBusExecutionException("Unsupported URI scheme")
            openUri(uri)
        }
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName] ?: throw DBusExecutionException("Unknown property")) as A
        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
            val unwrapped = if (value is Variant<*>) value.value else value
            if (interfaceName == MPRIS_PLAYER && propertyName == "Rate" && unwrapped is Number) {
                val speed = unwrapped.toFloat()
                if (speed.isFinite() && speed in 0.25f..4f) { controls.submit(DesktopTransportAction.Speed(speed)); return }
                throw DBusExecutionException("Invalid playback rate")
            }
            throw DBusExecutionException("Property is read-only or unsupported")
        }
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            MPRIS_PLAYER -> playerProperties(published.get())
            MPRIS_ROOT -> mapOf(
                "Identity" to Variant(DesktopStrings["app_name"]), "DesktopEntry" to Variant("archivetune"),
                "CanQuit" to Variant(true), "CanRaise" to Variant(true), "HasTrackList" to Variant(false),
                "SupportedUriSchemes" to Variant(listOf(MPRIS_URI_SCHEME), "as"),
                "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
            )
            else -> throw DBusExecutionException("Unknown interface")
        }
    }

    inner class Tray : StatusNotifierItem, Properties {
        override fun getObjectPath() = SNI_PATH
        override fun Activate(x: Int, y: Int) = raise()
        override fun SecondaryActivate(x: Int, y: Int) = controls.submit(DesktopTransportAction.Toggle)
        override fun ContextMenu(x: Int, y: Int) = showMenu(x, y)
        override fun Scroll(delta: Int, orientation: String) {
            if (orientation == "horizontal" && delta != 0) controls.submit(
                if (delta > 0) DesktopTransportAction.Next else DesktopTransportAction.Previous,
            )
        }
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName] ?: throw DBusExecutionException("Unknown property")) as A
        override fun <A> Set(interfaceName: String, propertyName: String, value: A) {
            throw DBusExecutionException("Read-only property")
        }
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> {
            if (interfaceName != SNI_INTERFACE) throw DBusExecutionException("Unknown interface")
            return mapOf(
                "Category" to Variant("ApplicationStatus"), "Id" to Variant("archivetune"),
                "Title" to Variant(DesktopStrings["app_name"]), "Status" to Variant("Active"),
                "WindowId" to Variant(UInt32(0)), "IconName" to Variant("archivetune"),
                "IconPixmap" to Variant(listOf(iconPixmap()), "a(iiay)"),
                "ItemIsMenu" to Variant(false), "Menu" to Variant(DBusPath("/Menu")),
            )
        }
    }

    inner class Menu : DbusMenu, Properties {
        private val labels = mapOf(1 to "show_window", 2 to "play_pause", 3 to "next_track", 4 to "previous_track", 5 to "quit")
        override fun getObjectPath() = "/Menu"
        private fun item(id: Int): Map<String, Variant<*>> = if (id == 0) mapOf("children-display" to Variant("submenu")) else
            mapOf("label" to Variant(DesktopStrings[labels[id] ?: throw DBusExecutionException("Unknown menu item")]),
                "enabled" to Variant(true), "visible" to Variant(true))
        override fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayoutReply<UInt32, MenuLayout> {
            val children = if (parentId == 0 && recursionDepth != 0) labels.keys.map { Variant(MenuLayout(it, item(it), emptyList()), "(ia{sv}av)") } else emptyList()
            return MenuLayoutReply(UInt32(1), MenuLayout(parentId, item(parentId), children))
        }
        override fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>) =
            (if (ids.isEmpty()) labels.keys.toList() else ids).map { MenuItemProperties(it, item(it).filterKeys { key -> propertyNames.isEmpty() || key in propertyNames }) }
        override fun GetProperty(id: Int, name: String) = item(id)[name] ?: throw DBusExecutionException("Unknown property")
        override fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32) {
            if (eventId != "clicked") return
            when (id) {
                1 -> raise()
                2 -> controls.submit(DesktopTransportAction.Toggle)
                3 -> media.Next()
                4 -> media.Previous()
                5 -> quit()
            }
        }
        override fun AboutToShow(id: Int) = false
        @Suppress("UNCHECKED_CAST")
        override fun <A> Get(interfaceName: String, propertyName: String): A =
            (GetAll(interfaceName)[propertyName] ?: throw DBusExecutionException("Unknown property")) as A
        override fun <A> Set(interfaceName: String, propertyName: String, value: A) { throw DBusExecutionException("Read-only property") }
        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = mapOf(
            "Version" to Variant(UInt32(3)), "TextDirection" to Variant("ltr"), "Status" to Variant("normal"),
            "IconThemePath" to Variant(emptyList<String>(), "as"),
        )
    }

    private fun showMenu(x: Int, y: Int) = java.awt.EventQueue.invokeLater {
        val anchor = javax.swing.JWindow().apply { setBounds(x, y, 1, 1); isVisible = true }
        val popup = javax.swing.JPopupMenu()
        val entries = listOf("show_window" to raise, "play_pause" to { controls.submit(DesktopTransportAction.Toggle) },
            "next_track" to { media.Next() }, "previous_track" to { media.Previous() }, "quit" to quit)
        entries.forEach { (label, action) -> popup.add(javax.swing.JMenuItem(DesktopStrings[label]).apply {
            addActionListener { action() }
        }) }
        popup.addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(event: javax.swing.event.PopupMenuEvent) = Unit
            override fun popupMenuWillBecomeInvisible(event: javax.swing.event.PopupMenuEvent) = anchor.dispose()
            override fun popupMenuCanceled(event: javax.swing.event.PopupMenuEvent) = anchor.dispose()
        })
        popup.show(anchor.contentPane, 0, 0)
    }

    private fun iconPixmap(): IconPixmap {
        // SNI mandates network-order ARGB, unlike the PNG embedded for native package launchers.
        val image = javax.imageio.ImageIO.read(checkNotNull(javaClass.getResource("/archivetune.png")))
        val bytes = ByteArray(32 * 32 * 4)
        for (y in 0 until 32) for (x in 0 until 32) {
            val argb = image.getRGB(x * image.width / 32, y * image.height / 32)
            val offset = (y * 32 + x) * 4
            for (channel in 0..3) bytes[offset + channel] = (argb ushr (24 - channel * 8)).toByte()
        }
        return IconPixmap(32, 32, bytes)
    }
    private fun micros(ms: Long) = ms.coerceIn(0, Long.MAX_VALUE / 1000) * 1000
}

/**
 * Mirrors the deep-link intake limits (length and control characters), so a bus caller cannot hand
 * the launch intake an oversized, control-character or foreign-scheme URI.
 */
private fun acceptsOpenUri(uri: String): Boolean =
    uri.length <= 8192 && uri.none { it.code < 32 } &&
        runCatching { java.net.URI(uri).scheme }.getOrNull().equals(MPRIS_URI_SCHEME, ignoreCase = true)
