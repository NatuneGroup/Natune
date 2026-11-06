package moe.rukamori.archivetune.desktop

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

/** How this launch draws its window decoration. */
enum class DesktopFrameMode {
    /** The window manager or DWM draws the title bar; the app draws no caption and no drag area. */
    System,

    /** The app draws its own caption: a Windows title bar with Snap Layouts, or a Linux 32 dp caption. */
    Custom,
}

/**
 * Which frame this window uses, decided once before the AWT window exists.
 *
 * The mode is fixed at startup because AWT cannot add or remove a window manager decoration after creation: X11 needs
 * an undecorated window to draw its own caption, and the Windows frame is removed in the window procedure. The stored
 * preference is mirrored to a small file at each launch's end, so the next launch reads it before the settings store
 * (which opens with the session graph) exists. The settings row writes the preference key; the shell mirrors it here.
 *
 * Wayland always takes the system frame: `_NET_WM_MOVERESIZE` is an X11 protocol, and a Wayland client that paints its
 * own caption cannot reliably move or resize the window. macOS keeps its native title bar for the same reason.
 */
object DesktopWindowFramePolicy {
    /** The preference key the settings row owns. The shell mirrors its value to [cacheFile] for the next launch. */
    const val PREFERENCE_NAME = "desktop_system_window_frame"

    private const val CACHE_FILE = "window-frame.properties"
    private const val CACHE_KEY = "system_frame"
    private val warned = AtomicBoolean()

    fun isWindows(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        osName.startsWith("Windows", ignoreCase = true)

    fun isMac(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        osName.startsWith("Mac", ignoreCase = true) || osName.startsWith("Darwin", ignoreCase = true)

    fun isLinux(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        osName.startsWith("Linux", ignoreCase = true)

    /** A Wayland session: the compositor sets WAYLAND_DISPLAY, and some sessions only report it in XDG_SESSION_TYPE. */
    fun isWayland(
        environment: Map<String, String> = System.getenv(),
        sessionType: String? = System.getenv("XDG_SESSION_TYPE"),
    ): Boolean = environment["WAYLAND_DISPLAY"]?.isNotBlank() == true ||
        sessionType?.equals("wayland", ignoreCase = true) == true

    /**
     * The frame this launch draws. A host the app cannot decorate itself keeps the system frame; everywhere else the
     * stored preference wins, and an unset preference means the app draws its own (which is the reference behaviour).
     */
    fun resolve(
        osName: String = System.getProperty("os.name").orEmpty(),
        wayland: Boolean = isWayland(),
        rememberedSystemFrame: Boolean? = null,
    ): DesktopFrameMode {
        if (isMac(osName) || wayland || (!isWindows(osName) && !isLinux(osName))) return DesktopFrameMode.System
        return if (rememberedSystemFrame == true) DesktopFrameMode.System else DesktopFrameMode.Custom
    }

    /** True when this mode draws its own caption, so the window is created without a decoration where that matters. */
    fun requiresUndecorated(mode: DesktopFrameMode, osName: String = System.getProperty("os.name").orEmpty()): Boolean =
        mode == DesktopFrameMode.Custom && isLinux(osName)

    fun cacheFile(instanceDirectory: Path): Path = instanceDirectory.resolve(CACHE_FILE)

    /** The mirrored preference, or null when this is the first launch or the file is unreadable. */
    fun read(instanceDirectory: Path): Boolean? = try {
        val file = cacheFile(instanceDirectory)
        if (!Files.isRegularFile(file)) {
            null
        } else {
            val values = Properties().apply { Files.newInputStream(file).use { load(it) } }
            values.getProperty(CACHE_KEY)?.toBooleanStrictOrNull()
        }
    } catch (failure: Exception) {
        if (warned.compareAndSet(false, true)) {
            System.err.println("window frame: stored mode unreadable (${failure.javaClass.simpleName})")
        }
        null
    }

    /** Mirrors the preference for the next launch; a failed write leaves the previous mode in place. */
    fun write(instanceDirectory: Path, systemFrame: Boolean) {
        try {
            Files.createDirectories(instanceDirectory)
            val target = cacheFile(instanceDirectory)
            val staging = target.resolveSibling("$CACHE_FILE.tmp")
            val values = Properties().apply { setProperty(CACHE_KEY, systemFrame.toString()) }
            Files.newOutputStream(staging).use { values.store(it, "window frame mode for the next launch") }
            try {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (failure: Exception) {
            if (warned.compareAndSet(false, true)) {
                System.err.println("window frame: stored mode not updated (${failure.javaClass.simpleName})")
            }
        }
    }
}
