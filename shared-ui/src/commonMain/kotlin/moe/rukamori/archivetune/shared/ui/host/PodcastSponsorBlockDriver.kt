package moe.rukamori.archivetune.shared.ui.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.provider.KtorSponsorBlockRepository
import moe.rukamori.archivetune.shared.data.provider.SkipSegment
import moe.rukamori.archivetune.shared.data.provider.SponsorBlockConfig
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockCategoriesKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockPodcastsEnabledKey

/**
 * Seeks past SponsorBlock segments in podcast episodes that play from YouTube. Songs are not handled here: the shared
 * player has no song skipping yet. The driver reads only the snapshots the player already publishes, so it adds no
 * ticker. Segments are looked up once per occurrence in a child job, which a track change cancels, so a slow lookup
 * never holds up the snapshot collector.
 */
class PodcastSponsorBlockDriver(
    private val scope: CoroutineScope,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val preferences: StateFlow<PreferenceSnapshot?>,
    private val repository: KtorSponsorBlockRepository,
) {
    private class Loaded(val occurrenceId: String, val segments: List<SkipSegment>)

    private var occurrenceId: String? = null
    private var lookup: Job? = null
    private val loaded = MutableStateFlow<Loaded?>(null)
    private val skippedEnds = HashSet<Long>()

    // Declared after every field it reads, because the collector may run before the constructor returns.
    private val job = scope.launch {
        player.snapshot.collect { snapshot -> observe(snapshot) }
    }

    /** Stops observing and cancels a lookup still in flight. */
    suspend fun close() {
        job.cancelAndJoin()
        reset()
    }

    private fun observe(snapshot: PlaybackSnapshot?) {
        if (snapshot == null) {
            reset()
            return
        }
        if (snapshot.occurrenceId != occurrenceId) {
            reset()
            occurrenceId = snapshot.occurrenceId
            val videoId = episodeVideoId(snapshot.occurrenceId)
            if (videoId != null) {
                val id = snapshot.occurrenceId
                lookup = scope.launch { loaded.value = Loaded(id, lookupSegments(videoId)) }
            }
        }
        val current = loaded.value?.takeIf { it.occurrenceId == snapshot.occurrenceId } ?: return
        if (!snapshot.isAudioAdvancing) return
        val position = player.positionMs() ?: snapshot.positionMs
        val segment = current.segments.firstOrNull { position >= it.startMs && position < it.endMs - SKIP_TAIL_MS }
            ?: return
        if (!skippedEnds.add(segment.endMs)) return
        // Landing past the end would finish the episode early, so a segment that runs to the end plays out.
        val duration = snapshot.durationMs
        if (duration != null && segment.endMs >= duration - SKIP_TAIL_MS) return
        player.seekTo(segment.endMs)
    }

    private fun reset() {
        lookup?.cancel()
        lookup = null
        loaded.value = null
        skippedEnds.clear()
        occurrenceId = null
    }

    /** The YouTube video id of [occurrenceId] when it is a podcast episode, null for every other item. */
    private fun episodeVideoId(occurrenceId: String): String? {
        val ref = queue.value.entries.firstOrNull { it.occurrenceId == occurrenceId }?.item?.ref ?: return null
        return ref.id.takeIf { ref.kind == ContentKind.EPISODE && ref.provider == ContentProvider.YOUTUBE.id }
    }

    /** Empty unless SponsorBlock and podcast skipping are both on; the repository itself never throws. */
    private suspend fun lookupSegments(videoId: String): List<SkipSegment> {
        val prefs = preferences.value ?: return emptyList()
        if (prefs[SponsorBlockEnabledKey] != true || prefs[SponsorBlockPodcastsEnabledKey] != true) return emptyList()
        val categories = prefs[SponsorBlockCategoriesKey] ?: DEFAULT_CATEGORIES
        if (categories.isEmpty()) return emptyList()
        return repository.segments(videoId, SponsorBlockConfig(enabled = true, categories = categories))
    }

    private companion object {
        /** Too close to the end to be worth a seek, and close enough that seeking may overshoot. */
        const val SKIP_TAIL_MS = 300L

        /** Matches the category the Android app and the settings screen default to. */
        val DEFAULT_CATEGORIES = setOf("music_offtopic")
    }
}
