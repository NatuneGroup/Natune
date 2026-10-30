package moe.rukamori.archivetune.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.menu.songExportFileName

/** Android's per-song session adjustment, not a new global persisted preference. */
internal class DesktopLyricsOffsets {
    private val offsets = mutableStateMapOf<ContentRef, Long>()
    fun value(ref: ContentRef?): Long = offsets[ref] ?: 0L
    fun adjust(ref: ContentRef, deltaMs: Long) {
        if (ref !in offsets && offsets.size >= 128) offsets.remove(offsets.keys.first())
        offsets[ref] = (value(ref) + deltaMs.coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)).coerceIn(-MAX_OFFSET_MS, MAX_OFFSET_MS)
    }
    fun reset(ref: ContentRef) { offsets.remove(ref) }
    private companion object { const val MAX_OFFSET_MS = 30_000L }
}

internal class DesktopLyricsTools(private val graph: DesktopSessionGraph) {
    val offsets = DesktopLyricsOffsets()
    private var shareJob: Job? = null
    var notice: String? by mutableStateOf(null)
        private set

    fun dismissNotice() { notice = null }

    fun share(card: ImageBitmap, title: String) {
        if (shareJob?.isActive == true) return
        try {
            check(!GraphicsEnvironment.isHeadless())
            require(card.width > 0 && card.height > 0 && card.width.toLong() * card.height <= MAX_CARD_PIXELS)
            shareJob = graph.scope.launch {
                try {
                    val file = chooseDesktopSaveFile(
                        DesktopStrings["content_lyrics_share_pick"], songExportFileName(title, "png"),
                    ) ?: return@launch
                    writeDesktopContentPng(card, file.toPath())
                    notice = "content_lyrics_share_saved"
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    notice = "content_lyrics_share_failed"
                    graph.report("lyrics card not saved: ${failure.javaClass.simpleName}")
                }
            }
        } catch (failure: Exception) {
            notice = "content_lyrics_share_failed"
            graph.report("lyrics card not captured: ${failure.javaClass.simpleName}")
        }
    }

    fun close() { shareJob?.cancel() }

    private companion object { const val MAX_CARD_PIXELS = 8_000_000L }
}

@Composable
internal fun DesktopLyricsShareNotice(tools: DesktopLyricsTools) {
    tools.notice?.let {
        AlertDialog(
            onDismissRequest = tools::dismissNotice,
            text = { Text(DesktopStrings[it]) },
            confirmButton = { TextButton(onClick = tools::dismissNotice) { Text(DesktopStrings["ok"]) } },
        )
    }
}
