package moe.rukamori.archivetune.shared.data.lyrics

import moe.rukamori.archivetune.shared.domain.lyrics.LyricsDetection

/**
 * Pure ranking over provider answers. Candidates arrive in provider priority order, so the tier
 * decides and priority only breaks ties inside a tier.
 */
internal object LyricsRanking {
    /** Word-synced beats line-synced beats plain. An empty list is [LyricsResult.NOT_FOUND]. */
    fun rank(candidates: List<LyricsResult>): LyricsResult =
        candidates.firstOrNull { LyricsDetection.hasWordSyncedLyrics(it.lyrics) }
            ?: candidates.firstOrNull { LyricsDetection.isLineSyncedLrc(it.lyrics) }
            ?: candidates.firstOrNull()
            ?: LyricsResult.NOT_FOUND

    fun firstWordSynced(candidates: List<LyricsResult>): LyricsResult? =
        candidates.firstOrNull { LyricsDetection.hasWordSyncedLyrics(it.lyrics) }
}
