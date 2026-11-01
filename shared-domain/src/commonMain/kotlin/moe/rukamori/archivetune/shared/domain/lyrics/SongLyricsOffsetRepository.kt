package moe.rukamori.archivetune.shared.domain.lyrics

import kotlinx.coroutines.flow.Flow
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/** Widest manual correction a song may carry; matching the legacy dialog's sane range keeps a stored value plausible. */
const val MAX_LYRICS_SYNC_OFFSET_MS = 30_000L

/**
 * The persisted per-song lyrics offset. A positive [offsetMs] delays the words relative to playback,
 * a negative one brings them earlier; null clears the stored correction back to the global offset alone.
 * Keyed by the content ref's provider and id, never by queue occurrence, so the same song keeps its
 * correction wherever it appears.
 */
interface SongLyricsOffsetRepository {
    fun observeOffset(song: ContentRef): Flow<Long?>

    suspend fun offset(song: ContentRef): Long?

    suspend fun setOffset(song: ContentRef, offsetMs: Long?)
}

/** The effective offset a renderer applies: the stored per-song correction plus the global preference. */
fun effectiveLyricsSyncOffsetMs(songOffsetMs: Long?, globalOffsetMs: Long): Long =
    (songOffsetMs ?: 0L) + globalOffsetMs

