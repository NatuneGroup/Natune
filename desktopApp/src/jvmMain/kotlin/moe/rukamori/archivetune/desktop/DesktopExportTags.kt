package moe.rukamori.archivetune.desktop

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import kotlinx.coroutines.*
import moe.rukamori.archivetune.playback.AudioTagger
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey

/** The retained Android tag writer logs only the failure type on the desktop host. */
internal object DesktopTagLog {
    fun w(error: Throwable, message: String, vararg args: Any?) { desktopDiagnostic("Audio tag write: ${error.javaClass.simpleName}") }
    fun w(message: String, vararg args: Any?) { desktopDiagnostic("Audio tag format did not accept artwork") }
}

internal suspend fun tagDesktopExport(http: HttpClient, item: ContentItem, lyrics: String?, file: Path) {
    val artwork = item.artworkUri?.takeIf { it.startsWith("https://") }?.let { uri ->
        try {
            withTimeoutOrNull(10_000L) {
                http.prepareGet(uri).execute { response ->
                    if (response.status.value !in 200..299) return@execute null
                    val output = ByteArrayOutputStream()
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer)
                        if (count < 0) break
                        if (count == 0) { yield(); continue }
                        if (output.size() + count > 8 * 1024 * 1024) return@execute null
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
    }
    withContext(Dispatchers.IO) {
        val tagged = AudioTagger.tag(file.toFile(), AudioTagger.Metadata(title = item.title,
            artist = item.artists.joinToString(", ") { it.name }.ifBlank { item.subtitle }, album = item.album,
            isrc = item.isrc, artworkBytes = artwork))
        if (!tagged) desktopDiagnostic("Exported audio container did not accept metadata tags")
        if (!lyrics.isNullOrBlank()) {
            try {
                val audio = AudioFileIO.read(file.toFile())
                audio.getTagOrCreateAndSetDefault().setField(FieldKey.LYRICS, lyrics)
                audio.commit()
            } catch (_: Exception) { desktopDiagnostic("Exported audio container did not accept lyrics") }
        }
    }
}
