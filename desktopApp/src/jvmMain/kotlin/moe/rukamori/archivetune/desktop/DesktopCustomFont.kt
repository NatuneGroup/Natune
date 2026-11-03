package moe.rukamori.archivetune.desktop

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.FileFont
import java.net.URI
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The custom font the settings page stored by file URI. Compose Desktop resolves a FileFont through Skia, so the
 * family is built straight from the stored file; a missing or unreadable file answers null and the default family
 * shows, matching the iOS loader's rule.
 */
internal suspend fun desktopCustomFontFamily(uri: String): FontFamily? = withContext(Dispatchers.Default) {
    val path = runCatching { Path.of(URI(uri)) }.getOrNull() ?: return@withContext null
    if (!path.exists()) return@withContext null
    @OptIn(ExperimentalTextApi::class)
    val family = FontFamily(FileFont(path.toFile()))
    family
}
