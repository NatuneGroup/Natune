/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.kmp.lyrics

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.LyricsEntity
import moe.rukamori.archivetune.shared.ui.menu.LyricsEditSnapshot
import moe.rukamori.archivetune.shared.ui.menu.LyricsMenuEdits

fun androidLyricsMenuEdits(database: MusicDatabase): LyricsMenuEdits = LyricsMenuEdits { expected, replacement, guard ->
    require(expected.mediaId == replacement.mediaId)
    database.withTransaction {
        val current = getLyricsById(expected.mediaId)
        currentCoroutineContext().ensureActive()
        if (current?.sharedEditSnapshot() != expected || !guard()) return@withTransaction null
        replaceLyrics(replacement.mediaId, replacement.lyrics, replacement.source, replacement.providerName)
        getLyricsById(replacement.mediaId)?.sharedEditSnapshot()
    }
}

fun LyricsEntity.sharedEditSnapshot(): LyricsEditSnapshot =
    LyricsEditSnapshot(id, lyrics, source, providerName, updatedAt)
