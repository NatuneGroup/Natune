package moe.rukamori.archivetune.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.integration.DesktopFilePickers
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.settings.ExternalDownloaderEnabledKey

/** Hands a public content URL to the user's installed yt-dlp; streaming credentials/URLs never leave the source owner. */
internal suspend fun desktopExternalDownload(graph: DesktopSessionGraph, ref: ContentRef): Boolean {
    if (ref.provider != ContentProvider.YOUTUBE.id || !Regex("[A-Za-z0-9_-]{11}").matches(ref.id)) return false
    return desktopExternalDownload(graph, "https://www.youtube.com/watch?v=${ref.id}")
}
internal suspend fun desktopExternalDownload(graph: DesktopSessionGraph, url: String): Boolean {
    if (graph.settings.snapshots.value?.get(ExternalDownloaderEnabledKey, false) != true) return false
    val uri = runCatching { io.ktor.http.Url(url) }.getOrNull() ?: return false
    if (uri.protocol.name != "https" || uri.host !in setOf("www.youtube.com", "music.youtube.com", "youtu.be") ||
        !uri.user.isNullOrEmpty() || !uri.password.isNullOrEmpty()) return false
    val executable = withContext(Dispatchers.IO) {
        val name = if (System.getProperty("os.name").startsWith("Windows")) "yt-dlp.exe" else "yt-dlp"
        System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).asSequence().filter { it.isNotBlank() }
            .map { Path.of(it).resolve(name) }.firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
    } ?: return false
    val folder = DesktopFilePickers().use { it.folder() } ?: return false
    return withContext(Dispatchers.IO) {
        try {
            ProcessBuilder(executable.toString(), "--no-playlist", "--extract-audio", "--paths", folder.toString(), "--", url)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            true
        } catch (_: java.io.IOException) { false }
    }
}
