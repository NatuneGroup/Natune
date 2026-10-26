/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.ui.session

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.ui.menu.LyricsEditSnapshot
import moe.rukamori.archivetune.shared.ui.menu.LyricsMenuEdits

/** Automatic and manual AI success use the same guarded durable edit/undo path as ordinary menus. */
class LyricsTranslationPersistence(
    scope: CoroutineScope,
    sessions: LyricsSessions,
    edits: LyricsMenuEdits,
    read: suspend (String) -> LyricsEditSnapshot?,
    onFailure: () -> Unit,
) {
    init {
        scope.launch {
            sessions.translation.state.collect { state ->
                val result = state.result ?: return@collect
                val translated = result.translatedLyrics ?: return@collect
                try {
                    val before = read(result.request.mediaId) ?: return@collect
                    if (before.lyrics == result.request.lyrics && sessions.isCurrent(result)) {
                        edits.apply(before, translated) { sessions.isCurrent(result) }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    if (sessions.isCurrent(result) && sessions.translation.cancelResult(result)) onFailure()
                }
            }
        }
    }
}
