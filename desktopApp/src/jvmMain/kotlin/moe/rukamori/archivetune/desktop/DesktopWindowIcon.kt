package moe.rukamori.archivetune.desktop

import java.awt.Image
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The app icon the window shows in its title bar, the taskbar and Alt-Tab. The packaged launcher gets that artwork from
 * jpackage's `.ico`, but the AWT window otherwise falls back to the JDK's coffee cup, so every size the window manager
 * asks for is rendered here from the one PNG in the application jar. Linux reads the same list for `_NET_WM_ICON`;
 * macOS takes the dock icon from the bundle and ignores it.
 */
internal object DesktopWindowIcon {
    /** 16 px title bar, 24/32 px switcher, 48/64 px taskbar at 150-200 % and 256 px for the large window list. */
    internal val ICON_SIZES = intArrayOf(16, 24, 32, 48, 64, 128, 256)

    private const val RESOURCE = "archivetune.png"

    private val loaded: List<Image> by lazy(::load)

    /** Called from the window's first composition, before the frame is shown, and never blocks it. */
    fun applyTo(window: java.awt.Window) {
        val images = loaded
        if (images.isEmpty()) return
        runCatching { window.setIconImages(images) }
            .onFailure { desktopDiagnostic("window icon: ${it.javaClass.simpleName}") }
    }

    internal fun load(): List<Image> = try {
        DesktopWindowIcon::class.java.getResourceAsStream("/$RESOURCE")?.use { scaled(ImageIO.read(it)) }.orEmpty()
    } catch (failure: Exception) {
        desktopDiagnostic("window icon unavailable: ${failure.javaClass.simpleName}")
        emptyList()
    }

    /** Every [ICON_SIZES] entry drawn from [source]; an exact match is reused instead of resampled. */
    internal fun scaled(source: BufferedImage?): List<Image> {
        if (source == null || source.width <= 0 || source.height <= 0) return emptyList()
        return ICON_SIZES.map { size ->
            if (source.width == size && source.height == size) {
                source
            } else {
                BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB).also { target ->
                    target.createGraphics().apply {
                        setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                        setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        drawImage(source, 0, 0, size, size, null)
                    }.dispose()
                }
            }
        }
    }
}
