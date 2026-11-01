package moe.rukamori.archivetune.desktop.integration.linux

import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.types.Variant

/** The freedesktop appearance namespace the xdg-desktop-portal settings object publishes. */
private const val PORTAL_APPEARANCE = "org.freedesktop.appearance"
private const val COLOR_SCHEME_KEY = "color-scheme"
private const val PORTAL_BUS = "org.freedesktop.portal.Desktop"
private const val PORTAL_PATH = "/org/freedesktop/portal/desktop"

/**
 * The OS light/dark answer under Linux desktops that publish the portal color-scheme setting (GNOME 42+, KDE 6).
 * [onDark] receives true/dark, false/light or null when the portal names no scheme, so the caller keeps its own
 * fallback rather than guessing. Read once at open and pushed through the portal's SettingChanged signal; a missing
 * portal is not an error — desktop environments without it simply keep the fallback.
 */
class LinuxAppearanceTheme(
    private val onDark: (Boolean?) -> Unit,
    private val connection: DBusConnection = DBusConnectionBuilder.forSessionBus().withShared(false).build(),
) : AutoCloseable {
    private val signals: AutoCloseable?

    init {
        signals = runCatching {
            connection.addSigHandler(PortalSettings.SettingChanged::class.java) { signal ->
                if (signal.namespace == PORTAL_APPEARANCE && signal.key == COLOR_SCHEME_KEY) {
                    onDark(colorSchemeDark(signal.value))
                }
            }
        }.getOrNull()
        onDark(readPortalDark())
    }

    private fun readPortalDark(): Boolean? = try {
        connection.getRemoteObject(PORTAL_BUS, PORTAL_PATH, PortalSettings::class.java)
            .Read(PORTAL_APPEARANCE, COLOR_SCHEME_KEY)
            .let { colorSchemeDark(it) }
    } catch (error: Exception) {
        null
    }

    override fun close() {
        signals?.close()
        connection.close()
    }
}

/**
 * The freedesktop color-scheme values: 0 no preference, 1 prefers dark, 2 prefers light. The portal wraps the answer
 * in nested variants, so they unwrap before the numeric read. Anything else is no preference.
 */
internal fun colorSchemeDark(value: Any?): Boolean? {
    var unwrapped = value
    while (unwrapped is Variant<*>) unwrapped = unwrapped.value
    return when ((unwrapped as? Number)?.toInt()) {
        1 -> true
        2 -> false
        else -> null
    }
}
