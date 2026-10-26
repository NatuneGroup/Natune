/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.menu

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.rukamori.archivetune.shared.domain.lyrics.document.LyricsDocument

/** Captured menu edit, not another lyrics repository or a replacement for the native entity. */
data class LyricsEditSnapshot(
    val mediaId: String, val lyrics: String, val source: String, val providerName: String,
    val revision: Long = 0,
)

data class LyricsUndoSnapshot(val before: LyricsEditSnapshot, val applied: LyricsEditSnapshot)

class LyricsMenuEdits(
    /**
     * Host MUST compare the whole expected snapshot AND invoke the guard inside the write transaction,
     * returning the actual persisted revision. Null means stale; throwing means a storage failure.
     */
    private val compareAndSet: suspend (LyricsEditSnapshot, LyricsEditSnapshot, () -> Boolean) -> LyricsEditSnapshot?,
) {
    private val lock = Mutex()
    private val mutableUndo = MutableStateFlow<LyricsUndoSnapshot?>(null)
    val undo = mutableUndo.asStateFlow()

    suspend fun translate(
        before: LyricsEditSnapshot, segments: Map<Int, String>, isCurrent: () -> Boolean = { true },
    ): Boolean = persistTranslation(before, LyricsDocument.parse(before.lyrics).rebuild(segments), "TRANSLATION", isCurrent)

    suspend fun apply(
        before: LyricsEditSnapshot, lyrics: String, isCurrent: () -> Boolean,
    ): Boolean = persistTranslation(before, lyrics, "AI_TRANSLATION", isCurrent)

    private suspend fun persistTranslation(
        before: LyricsEditSnapshot, lyrics: String, source: String, isCurrent: () -> Boolean,
    ): Boolean = lock.withLock {
        if (!isCurrent() || lyrics.isBlank() || lyrics == before.lyrics) return@withLock false
        val saved = compareAndSet(before, before.copy(lyrics = lyrics, source = source), isCurrent)
            ?: return@withLock false
        // A second translation still undoes to the original provider text, never to AI's previous pass.
        val prior = mutableUndo.value
        val original = if (prior?.applied == before) prior.before else before
        mutableUndo.value = LyricsUndoSnapshot(original, saved)
        true
    }

    suspend fun undo(isCurrent: () -> Boolean = { true }): Boolean = lock.withLock {
        val snapshot = mutableUndo.value ?: return@withLock false
        if (!isCurrent() || compareAndSet(snapshot.applied, snapshot.before, isCurrent) == null) return@withLock false
        mutableUndo.value = null
        true
    }
}
