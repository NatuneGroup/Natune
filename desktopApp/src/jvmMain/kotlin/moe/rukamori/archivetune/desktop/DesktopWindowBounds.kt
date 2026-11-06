package moe.rukamori.archivetune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val BOUNDS_FILE = "window-bounds.properties"
// The smallest window the three-column layouts are built for: below this the navigation, player and queue controls clip.
private const val MIN_WIDTH = 800f
private const val MIN_HEIGHT = 600f
private const val MAX_EDGE = 8192f

/** The window as it was when the app last quit. [position] is null when it should open where the system puts it. */
internal class DesktopSavedWindow(val position: WindowPosition, val size: DpSize, val placement: WindowPlacement)

/**
 * Keeps the main window's size, position and maximized state between launches, as Metrolist's desktop app does. A
 * saved position that no connected screen shows any more is dropped, so the window never opens off-screen.
 */
internal object DesktopWindowBounds {
    private val defaultSize = DpSize(1024.dp, 720.dp)
    private val atomicMoveWarning = AtomicBoolean()

    /** Applies the minimum window size to the AWT window, in its device pixels, so no drag or saved size goes below it. */
    fun applyMinimumSize(window: java.awt.Window, density: Float) {
        window.minimumSize = java.awt.Dimension(
            (MIN_WIDTH * density).roundToInt(),
            (MIN_HEIGHT * density).roundToInt(),
        )
    }

    fun load(instanceDirectory: Path): DesktopSavedWindow {
        val fallback = DesktopSavedWindow(WindowPosition.PlatformDefault, defaultSize, WindowPlacement.Floating)
        val file = instanceDirectory.resolve(BOUNDS_FILE)
        val values = try {
            if (!Files.isRegularFile(file)) return fallback
            Properties().apply { Files.newInputStream(file).use { load(it) } }
        } catch (_: Exception) {
            return fallback
        }
        val width = values.getProperty("width")?.toFloatOrNull()?.coerceIn(MIN_WIDTH, MAX_EDGE) ?: return fallback
        val height = values.getProperty("height")?.toFloatOrNull()?.coerceIn(MIN_HEIGHT, MAX_EDGE) ?: return fallback
        val x = values.getProperty("x")?.toFloatOrNull()
        val y = values.getProperty("y")?.toFloatOrNull()
        val position = if (x != null && y != null && visibleOnAnyScreen(x, y, width, height)) {
            WindowPosition(x.dp, y.dp)
        } else {
            WindowPosition.PlatformDefault
        }
        val placement = if (values.getProperty("maximized") == "true") WindowPlacement.Maximized else WindowPlacement.Floating
        return DesktopSavedWindow(position, DpSize(width.dp, height.dp), placement)
    }

    fun save(instanceDirectory: Path, state: WindowState) =
        save(instanceDirectory, state) { staged, target, atomic -> moveBounds(staged, target, atomic) }

    /*
     * ATOMIC_MOVE is optional: FAT/exFAT, some SMB/NFS mounts and some Windows configurations refuse it.
     * Falling back to a replacing move keeps the saved window working there instead of failing every exit.
     * The injected move keeps that fallback testable without such a mount.
     */
    internal fun save(instanceDirectory: Path, state: WindowState, move: (Path, Path, Boolean) -> Unit) {
        val values = Properties()
        values.setProperty("width", state.size.width.value.toString())
        values.setProperty("height", state.size.height.value.toString())
        val position = state.position
        if (position is WindowPosition.Absolute) {
            values.setProperty("x", position.x.value.toString())
            values.setProperty("y", position.y.value.toString())
        }
        values.setProperty("maximized", (state.placement == WindowPlacement.Maximized).toString())
        val target = instanceDirectory.resolve(BOUNDS_FILE)
        val staged = instanceDirectory.resolve("$BOUNDS_FILE.tmp")
        try {
            Files.newOutputStream(staged).use { values.store(it, null) }
            try {
                move(staged, target, true)
            } catch (_: AtomicMoveNotSupportedException) {
                if (atomicMoveWarning.compareAndSet(false, true)) {
                    desktopDiagnostic("window bounds: atomic rename unsupported, using a replacing move")
                }
                move(staged, target, false)
            }
        } catch (failure: Exception) {
            runCatching { Files.deleteIfExists(staged) }
            desktopDiagnostic("window bounds not saved: ${failure.javaClass.simpleName}")
        }
    }

    private fun moveBounds(staged: Path, target: Path, atomic: Boolean) {
        if (atomic) {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } else {
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /* At least a title-bar-sized strip of the window has to land on a screen for the saved position to be kept. */
    private fun visibleOnAnyScreen(x: Float, y: Float, width: Float, height: Float): Boolean = try {
        if (GraphicsEnvironment.isHeadless()) {
            false
        } else {
            val window = Rectangle(x.toInt(), y.toInt(), width.toInt(), minOf(height, 48f).toInt())
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.any { device ->
                device.defaultConfiguration.bounds.intersection(window).let { !it.isEmpty && it.width >= 96 }
            }
        }
    } catch (_: Exception) {
        false
    }
}
