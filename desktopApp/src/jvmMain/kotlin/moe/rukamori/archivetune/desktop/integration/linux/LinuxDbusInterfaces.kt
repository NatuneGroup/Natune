/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.desktop.integration.linux

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal
import org.freedesktop.dbus.types.UInt32
import org.freedesktop.dbus.types.Variant

const val MPRIS_PATH = "/org/mpris/MediaPlayer2"
const val MPRIS_ROOT = "org.mpris.MediaPlayer2"
const val MPRIS_PLAYER = "org.mpris.MediaPlayer2.Player"
const val SNI_PATH = "/StatusNotifierItem"
const val SNI_INTERFACE = "org.kde.StatusNotifierItem"
/** The one scheme MPRIS OpenUri accepts; the same value is advertised as SupportedUriSchemes. */
const val MPRIS_URI_SCHEME = "archivetune"

interface StringVariantMap : TypeRef<Map<String, Variant<*>>>
interface StringArray : TypeRef<List<String>>

@DBusInterfaceName(MPRIS_ROOT)
@DBusProperty(name = "Identity", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanQuit", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanRaise", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "HasTrackList", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "DesktopEntry", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedUriSchemes", type = StringArray::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "SupportedMimeTypes", type = StringArray::class, access = DBusProperty.Access.READ)
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName(MPRIS_PLAYER)
@DBusProperty(name = "PlaybackStatus", type = String::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Metadata", type = StringVariantMap::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Position", type = Long::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Rate", type = Double::class)
@DBusProperty(name = "MinimumRate", type = Double::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "MaximumRate", type = Double::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "Volume", type = Double::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanGoNext", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanPlay", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanPause", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanSeek", type = Boolean::class, access = DBusProperty.Access.READ)
@DBusProperty(name = "CanControl", type = Boolean::class, access = DBusProperty.Access.READ)
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}

@DBusInterfaceName("org.kde.StatusNotifierWatcher")
interface StatusNotifierWatcher : DBusInterface {
    fun RegisterStatusNotifierItem(service: String)
}

@DBusInterfaceName(SNI_INTERFACE)
interface StatusNotifierItem : DBusInterface {
    fun Activate(x: Int, y: Int)
    fun SecondaryActivate(x: Int, y: Int)
    fun ContextMenu(x: Int, y: Int)
    fun Scroll(delta: Int, orientation: String)
}

class IconPixmap(
    @field:Position(0) val width: Int,
    @field:Position(1) val height: Int,
    @field:Position(2) val bytes: ByteArray,
) : Struct()

class MenuLayout(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
    @field:Position(2) val children: List<Variant<*>>,
) : Struct()

class MenuLayoutReply<A, B>(
    @field:Position(0) val revision: A,
    @field:Position(1) val layout: B,
) : Tuple()

class MenuItemProperties(
    @field:Position(0) val id: Int,
    @field:Position(1) val properties: Map<String, Variant<*>>,
) : Struct()

@DBusInterfaceName("com.canonical.dbusmenu")
interface DbusMenu : DBusInterface {
    fun GetLayout(parentId: Int, recursionDepth: Int, propertyNames: List<String>): MenuLayoutReply<UInt32, MenuLayout>
    fun GetGroupProperties(ids: List<Int>, propertyNames: List<String>): List<MenuItemProperties>
    fun GetProperty(id: Int, name: String): Variant<*>
    fun Event(id: Int, eventId: String, data: Variant<*>, timestamp: UInt32)
    fun AboutToShow(id: Int): Boolean
}
