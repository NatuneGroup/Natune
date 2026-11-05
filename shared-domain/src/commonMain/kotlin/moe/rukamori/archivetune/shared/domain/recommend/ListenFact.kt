package moe.rukamori.archivetune.shared.domain.recommend

import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentRef

/**
 * One recorded listen in the shape the recommendation policy needs. The host already stores every field here:
 * `Event.playTime` and `SongEntity.duration` (or the occurrence's provider duration) give the outcome, the song's
 * credits give artist and album identity, and the event timestamp gives decay and context.
 *
 * [durationMs] is null when the provider (or the Android `SongEntity` default of -1) did not report a duration. A
 * listen without a duration can never be [ListeningOutcome.COMPLETED]; see [ListeningOutcome.classify].
 *
 * [wasRecommended] marks a tail or mix item. A completed autoplay item is weaker evidence of taste than a track the
 * listener chose, so the profile halves its weight. [sourceArm] is the candidate source that produced it, ready for
 * the outcome feedback loop.
 */
data class ListenFact(
    val ref: ContentRef,
    val artistIds: List<String>,
    val albumId: String?,
    val playedMs: Long,
    val durationMs: Long?,
    val atMs: Long,
    val wasRecommended: Boolean = false,
    val sourceArm: String? = null,
) {
    init {
        require(ref.kind == ContentKind.SONG) { "A recommendation listen is a song, not ${ref.kind}" }
        require(playedMs >= 0L) { "A listen cannot have negative play time" }
        require(durationMs == null || durationMs > 0L) {
            "Unknown durations become null; $durationMs is not a provider duration"
        }
        require(artistIds.all(String::isNotBlank)) { "Artist identities cannot be blank" }
        require(albumId == null || albumId.isNotBlank()) { "An album identity cannot be blank" }
    }

    val outcome: ListeningOutcome
        get() = ListeningOutcome.classify(playedMs, durationMs)

    /** Autoplay evidence is deliberately weaker than a deliberate play. */
    val sourceWeight: Double
        get() = if (wasRecommended) RECOMMENDED_SOURCE_WEIGHT else CHOSEN_SOURCE_WEIGHT

    companion object {
        const val RECOMMENDED_SOURCE_WEIGHT = 0.5
        const val CHOSEN_SOURCE_WEIGHT = 1.0
    }
}
