package moe.rukamori.archivetune.shared.ui.ios

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.domain.lyrics.MAX_LYRICS_SYNC_OFFSET_MS
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.ui.menu.LyricsEditSnapshot
import moe.rukamori.archivetune.shared.ui.menu.LyricsMenuEdits
import moe.rukamori.archivetune.shared.ui.menu.LyricsToolsBinding
import moe.rukamori.archivetune.shared.ui.session.LyricsTranslationPersistence
import moe.rukamori.archivetune.shared.ui.resources.*
import org.jetbrains.compose.resources.stringResource

internal class IosLyricsTools(private val graph: IosSessionGraph) : LyricsToolsBinding {
    override var editOpen by mutableStateOf(false)
    override var timingTarget by mutableStateOf<ContentRef?>(null)
    val edits = LyricsMenuEdits { before, after, guard ->
        if (graph.lyrics.replaceRaw(before.mediaId, before.lyrics, before.revision, after.lyrics, guard))
            after.copy(revision = graph.lyrics.revision) else null
    }
    private val translation = LyricsTranslationPersistence(graph.scope, graph.lyricsSurface.sessions, edits,
        read = { snapshot(it) }, onFailure = { graph.report("AI lyric edit could not be persisted") })
    fun snapshot(id: String? = graph.lyrics.current.value?.occurrenceId): LyricsEditSnapshot? =
        graph.lyrics.current.value?.takeIf { it.occurrenceId == id && it.hasLines }?.let {
            LyricsEditSnapshot(it.occurrenceId, it.raw, "HOST", "shared", graph.lyrics.revision)
        }
    private var editTargetId: String? = null
    fun openEdit(id: String) { editTargetId = id; editOpen = true }
    override fun currentSnapshot(): LyricsEditSnapshot? = snapshot(editTargetId ?: graph.lyrics.current.value?.occurrenceId)
    override suspend fun applyEdit(before: LyricsEditSnapshot, text: String): Boolean = edits.apply(before, text) { snapshot() == before }
    override suspend fun readOffset(ref: ContentRef): Long? = graph.songLyricsOffsets.offset(ref)
    override suspend fun saveOffset(ref: ContentRef, value: Long?) { graph.songLyricsOffsets.setOffset(ref, value) }
    override fun closeEdit() { editOpen = false; editTargetId = null }
    override fun closeTiming() { timingTarget = null }
}

@Composable
internal fun IosLyricsToolsDialogs(graph: IosSessionGraph) {
    moe.rukamori.archivetune.shared.ui.menu.LyricsToolsDialogs(graph.lyricsTools) { graph.report("lyrics change could not be saved") }
}
