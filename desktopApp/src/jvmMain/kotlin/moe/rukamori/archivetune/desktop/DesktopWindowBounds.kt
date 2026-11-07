package moe.rukamori.archivetune.desktop

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Dimension
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
private const val MIN_HEIGHT = 500f
private const val MAX_EDGE = 8192f
// At least a title-bar-sized strip of the window has to land on a screen for a saved position to be kept.
private const val TITLE_STRIP_DP = 48f
private const val MIN_VISIBLE_WIDTH_DP = 96f
// A saved value outside this range is a corrupt file, not a display; the bound also keeps the px conversion finite.
private const val MAX_POSITION_DP = 100_000f

/** The window as it was when the app last quit. [position] is null when it should open where the system puts it. */
internal class DesktopSavedWindow(val position: WindowPosition, val size: DpSize, val placement: WindowPlacement)

/**
 * The screen facts the window bounds need. [screens] and [workArea] are AWT device pixels; [scale] is the display's
 * user-space scale, which is also Compose's window density. A saved bound is dp, so every comparison happens after the
 * dp value has been multiplied by [scale] — the two unit systems are never mixed. [defaultScreen] lets a window restored
 * on a second monitor use that monitor's work area instead of the primary one's.
 */
internal class DesktopScreenSpace(
    val screens: List<Rectangle>,
    val workArea: Rectangle?,
    val scale: Float,
    val defaultScreen: Rectangle? = null,
) {
    companion object {
        /** No display answer: positions fall back to the platform default and sizes stay as saved. */
        val UNKNOWN = DesktopScreenSpace(emptyList(), null, 1f)
    }
}

/** The scale a screen space really uses; a broken or zero transform must not divide by zero. */
private val DesktopScreenSpace.safeScale: Float
    get() = scale.takeIf { it.isFinite() && it > 0f } ?: 1f

/**
 * Keeps the main window's size, position and maximized state between launches, as Metrolist's desktop app does. A
 * saved position that no connected screen shows any more is dropped, so the window never opens off-screen, and a saved
 * size is fitted to the screen work area, so a 1024x720 dp window still fits a 125-150 % laptop screen above the taskbar.
 */
internal object DesktopWindowBounds {
    private val defaultSize = DpSize(1080.dp, 720.dp)
    private val atomicMoveWarning = AtomicBoolean()

    /**
     * Applies the minimum window size to the AWT window, clamped to the work area, so neither a drag nor a saved size
     * goes below the layout minimum and a scaled small screen can still fit the window above its taskbar.
     */
    fun applyMinimumSize(window: java.awt.Window, density: Float) {
        window.minimumSize = minimumWindowSize(currentScreenSpace().copyScale(density))
    }

    fun load(instanceDirectory: Path): DesktopSavedWindow = load(instanceDirectory, currentScreenSpace())

    internal fun load(instanceDirectory: Path, space: DesktopScreenSpace): DesktopSavedWindow {
        val fallback = DesktopSavedWindow(
            position = WindowPosition.PlatformDefault,
            size = fitToWorkArea(defaultSize, space),
            placement = WindowPlacement.Floating,
        )
        val file = instanceDirectory.resolve(BOUNDS_FILE)
        val values = try {
            if (!Files.isRegularFile(file)) return fallback
            Properties().apply { Files.newInputStream(file).use { load(it) } }
        } catch (_: Exception) {
            return fallback
        }
        return decodeSavedWindow(values, space) ?: fallback
    }

    /** The saved file as a window, or null when the size is missing or unusable, which leaves the caller's default. */
    internal fun decodeSavedWindow(values: Properties, space: DesktopScreenSpace): DesktopSavedWindow? {
        val width = values.getProperty("width")?.toFloatOrNull()?.coerceIn(MIN_WIDTH, MAX_EDGE) ?: return null
        val height = values.getProperty("height")?.toFloatOrNull()?.coerceIn(MIN_HEIGHT, MAX_EDGE) ?: return null
        val saved = DpSize(width.dp, height.dp)
        val savedX = values.getProperty("x")?.toFloatOrNull()?.coerceIn(-MAX_POSITION_DP, MAX_POSITION_DP)
        val savedY = values.getProperty("y")?.toFloatOrNull()?.coerceIn(-MAX_POSITION_DP, MAX_POSITION_DP)
        val target = savedX?.let { x -> savedY?.let { y -> x to y } }
        val onScreen = target != null && positionIsUsable(target.first, target.second, saved, space)
        // A window restored on a second monitor is fitted to that monitor, not to the primary one.
        val workArea = if (onScreen && target != null) workAreaForPosition(target.first, target.second, space) else space.workArea
        val size = fitToWorkArea(saved, workArea, space.safeScale)
        val position = if (onScreen && target != null && positionIsUsable(target.first, target.second, size, space)) {
            WindowPosition(target.first.dp, target.second.dp)
        } else {
            WindowPosition.PlatformDefault
        }
        val placement = if (values.getProperty("maximized") == "true") WindowPlacement.Maximized else WindowPlacement.Floating
        return DesktopSavedWindow(position, size, placement)
    }

    /**
     * A dp size can never be larger than the screen work area, which is in device pixels: divide it by the scale first.
     * At 150 % on 1920x1080 the work area is about 1280x693 dp, so the default 720 dp height becomes 693 dp instead of
     * opening with the transport strip under the taskbar. A work area smaller than the layout minimum wins over it.
     */
    internal fun fitToWorkArea(size: DpSize, space: DesktopScreenSpace): DpSize =
        fitToWorkArea(size, space.workArea, space.safeScale)

    internal fun fitToWorkArea(size: DpSize, workArea: Rectangle?, scale: Float): DpSize {
        if (workArea == null) return size
        val availableWidth = (workArea.width / scale).coerceAtLeast(1f)
        val availableHeight = (workArea.height / scale).coerceAtLeast(1f)
        val width = size.width.value.coerceAtMost(availableWidth).coerceIn(minOf(MIN_WIDTH, availableWidth), availableWidth)
        val height = size.height.value.coerceAtMost(availableHeight).coerceIn(minOf(MIN_HEIGHT, availableHeight), availableHeight)
        return DpSize(width.dp, height.dp)
    }

    /**
     * The work area of the screen that holds a saved dp position. AWT only reports the primary screen's work area, so the
     * taskbar inset it implies is applied to the target screen's bounds; when no screen holds the position the caller
     * keeps the primary work area.
     */
    internal fun workAreaForPosition(xDp: Float, yDp: Float, space: DesktopScreenSpace): Rectangle? {
        val scale = space.safeScale
        val point = Rectangle((xDp * scale).roundToInt(), (yDp * scale).roundToInt(), 1, 1)
        val screen = space.screens.firstOrNull { it.contains(point) } ?: return space.workArea
        val primary = space.defaultScreen ?: return space.workArea
        val primaryWork = space.workArea ?: return screen
        val insetLeft = (primaryWork.x - primary.x).coerceAtLeast(0)
        val insetTop = (primaryWork.y - primary.y).coerceAtLeast(0)
        val insetRight = (primary.x + primary.width - primaryWork.x - primaryWork.width).coerceAtLeast(0)
        val insetBottom = (primary.y + primary.height - primaryWork.y - primaryWork.height).coerceAtLeast(0)
        return Rectangle(
            screen.x + insetLeft,
            screen.y + insetTop,
            (screen.width - insetLeft - insetRight).coerceAtLeast(1),
            (screen.height - insetTop - insetBottom).coerceAtLeast(1),
        )
    }

    /**
     * The layout minimum in AWT device pixels, the unit `Window.minimumSize` shares with the window bounds Compose sets
     * from dp, so the scale is applied once here. It is capped at the work area so a scaled 1366x768 screen keeps a
     * resizable window even though 600 dp is taller than its logical height there.
     */
    internal fun minimumWindowSize(space: DesktopScreenSpace): Dimension {
        val scale = space.safeScale
        val workArea = space.workArea
        val width = MIN_WIDTH * scale
        val height = MIN_HEIGHT * scale
        return Dimension(
            (workArea?.let { minOf(width, it.width.toFloat()) } ?: width).roundToInt().coerceAtLeast(1),
            (workArea?.let { minOf(height, it.height.toFloat()) } ?: height).roundToInt().coerceAtLeast(1),
        )
    }

    /**
     * Whether a saved dp position still lands a title-bar strip and a usable width on a connected screen. The screens are
     * device pixels, so the position and size are converted with the display scale before the intersection.
     */
    internal fun positionIsUsable(x: Float, y: Float, size: DpSize, space: DesktopScreenSpace): Boolean {
        if (space.screens.isEmpty()) return false
        val scale = space.safeScale
        fun device(value: Float): Int = (value * scale).roundToInt()
        val window = Rectangle(
            device(x),
            device(y),
            device(size.width.value).coerceAtLeast(1),
            device(minOf(size.height.value, TITLE_STRIP_DP)).coerceAtLeast(1),
        )
        val minimumVisibleWidth = device(MIN_VISIBLE_WIDTH_DP).coerceAtLeast(1)
        return space.screens.any { screen ->
            screen.intersection(window).let { visible -> !visible.isEmpty && visible.width >= minimumVisibleWidth }
        }
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

    /**
     * The connected screens, their work area and the display scale, or [DesktopScreenSpace.UNKNOWN] when the toolkit
     * cannot answer. Only the toolkit call is impure; every rule above is a pure function over this value.
     */
    private fun currentScreenSpace(): DesktopScreenSpace = try {
        if (GraphicsEnvironment.isHeadless()) {
            DesktopScreenSpace.UNKNOWN
        } else {
            val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
            val configuration = environment.defaultScreenDevice?.defaultConfiguration
            DesktopScreenSpace(
                screens = environment.screenDevices.map { it.defaultConfiguration.bounds },
                workArea = environment.maximumWindowBounds,
                // AffineTransform.scaleX is a double; the space carries the float Compose's density uses.
                scale = configuration?.defaultTransform?.scaleX?.toFloat() ?: 1f,
                defaultScreen = configuration?.bounds,
            )
        }
    } catch (_: Exception) {
        DesktopScreenSpace.UNKNOWN
    }

    private fun DesktopScreenSpace.copyScale(density: Float): DesktopScreenSpace =
        if (!density.isFinite() || density <= 0f) this else DesktopScreenSpace(screens, workArea, density)
}
