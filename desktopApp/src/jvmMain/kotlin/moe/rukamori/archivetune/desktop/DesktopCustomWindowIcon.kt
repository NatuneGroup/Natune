package moe.rukamori.archivetune.desktop

import androidx.compose.runtime.*
import java.awt.EventQueue
import java.awt.Window
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.shared.domain.settings.DesktopAppIconPathKey

@Composable
internal fun DesktopCustomWindowIcon(window: Window, graph: DesktopSessionGraph) {
    val preferences by graph.settings.snapshots.collectAsState()
    val path = preferences?.get(DesktopAppIconPathKey)
    LaunchedEffect(window, path) {
        val images = path?.let { withContext(Dispatchers.IO) {
            runCatching {
                val file = Path.of(it)
                require(Files.size(file) <= 1024 * 1024)
                ImageIO.createImageInputStream(file.toFile()).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    require(readers.hasNext())
                    val reader = readers.next()
                    try {
                        reader.input = input
                        require(reader.getWidth(0) in 1..1024 && reader.getHeight(0) in 1..1024)
                        DesktopWindowIcon.scaled(reader.read(0))
                    } finally { reader.dispose() }
                }
            }.getOrNull()
        } }
        EventQueue.invokeLater { if (images.isNullOrEmpty()) DesktopWindowIcon.applyTo(window) else window.iconImages = images }
    }
}
