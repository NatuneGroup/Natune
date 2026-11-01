package moe.rukamori.archivetune.desktop

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.desktop.integration.DesktopStrings
import moe.rukamori.archivetune.shared.domain.lyrics.MAX_LYRICS_SYNC_OFFSET_MS
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.menu.songExportFileName

/**
 * The per-song lyrics offset, persisted through the portable store instead of the session map it replaces,
 * so a correction survives restarts and is shared with every other surface that reads the same store.
 */
internal class DesktopLyricsOffsets(private val graph: DesktopSessionGraph) {
    fun observe(ref: ContentRef?): Flow<Long?> = ref?.let(graph.songLyricsOffsets::observeOffset) ?: flowOf(null)
    fun adjust(ref: ContentRef, deltaMs: Long) {
        graph.scope.launch {
            val next = ((graph.songLyricsOffsets.offset(ref) ?: 0L) + deltaMs)
                .coerceIn(-MAX_LYRICS_SYNC_OFFSET_MS, MAX_LYRICS_SYNC_OFFSET_MS)
            graph.songLyricsOffsets.setOffset(ref, next)
        }
    }
    fun reset(ref: ContentRef) {
        graph.scope.launch { graph.songLyricsOffsets.setOffset(ref, null) }
    }
}

internal class DesktopLyricsTools(private val graph: DesktopSessionGraph) {
    val offsets = DesktopLyricsOffsets(graph)
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
