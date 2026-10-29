package moe.rukamori.archivetune.shared.ui.host

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.shared.data.provider.SkipSegment
import moe.rukamori.archivetune.shared.data.provider.SponsorBlockConfig
import moe.rukamori.archivetune.shared.data.provider.SponsorBlockSegmentSource
import moe.rukamori.archivetune.shared.domain.playback.MusicPlayerController
import moe.rukamori.archivetune.shared.domain.playback.PlaybackSnapshot
import moe.rukamori.archivetune.shared.domain.playback.SponsorBlockPlaybackPolicy
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentProvider
import moe.rukamori.archivetune.shared.domain.repository.ContentRef
import moe.rukamori.archivetune.shared.domain.repository.QueueState
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockApiUrlKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockCategoriesKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.SponsorBlockPodcastsEnabledKey

/**
 * Seeks past SponsorBlock segments in podcast episodes that play from YouTube. Songs are not handled here: the shared
 * player has no song skipping yet. The driver reads only the snapshots the player already publishes, so it adds no
 * ticker. Segment lookups are occurrence- and settings-bound child jobs, so switching tracks/accounts or disabling
 * the podcast opt-in discards an in-flight response rather than continuing to skip cached segments.
 */
class PodcastSponsorBlockDriver(
    private val scope: CoroutineScope,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val preferences: StateFlow<PreferenceSnapshot?>,
    private val repository: SponsorBlockSegmentSource,
) {
    private data class LookupKey(val occurrenceId: String, val generation: Long, val videoId: String, val config: SponsorBlockConfig)
    private class Loaded(val key: LookupKey, val segments: List<SkipSegment>)

    private var requested: LookupKey? = null
    private var lookup: Job? = null
    private val loaded = MutableStateFlow<Loaded?>(null)
    private val skippedEnds = HashSet<Long>()

    // Declared after every field it reads, because the collector may run before the constructor returns.
    private val job = scope.launch {
        combine(player.snapshot, queue, preferences, loaded) { snapshot, _, _, _ -> snapshot }
            .collect { snapshot -> observe(snapshot) }
    }

    /** Stops observing and cancels a lookup still in flight. */
    suspend fun close() {
        job.cancelAndJoin()
        reset()
    }

    private fun observe(snapshot: PlaybackSnapshot?) {
        val key = snapshot?.let(::lookupKey)
        if (snapshot == null || key == null) {
            reset()
            return
        }
        if (key != requested) {
            reset()
            requested = key
            lookup = scope.launch {
                val segments = repository.segments(key.videoId, key.config)
                if (requested == key) loaded.value = Loaded(key, segments)
            }
        }
        val current = loaded.value?.takeIf { it.key == key } ?: return
        if (!snapshot.isAudioAdvancing || !snapshot.isSeekable) return
        val position = player.positionMs() ?: snapshot.positionMs
        val segment = current.segments.firstOrNull { position >= it.startMs && position < it.endMs - SKIP_TAIL_MS }
            ?: return
        // Landing past the end would finish the episode early, so a segment that runs to the end plays out.
        val duration = snapshot.durationMs
        if (duration != null && segment.endMs >= duration - SKIP_TAIL_MS) return
        if (!skippedEnds.add(segment.endMs)) return
        player.seekTo(segment.endMs)
    }

    private fun reset() {
        lookup?.cancel()
        lookup = null
        loaded.value = null
        skippedEnds.clear()
        requested = null
    }

    private fun lookupKey(snapshot: PlaybackSnapshot): LookupKey? {
        val ref = queue.value.entries.firstOrNull { it.occurrenceId == snapshot.occurrenceId }?.item?.ref ?: return null
        val config = podcastSponsorBlockConfig(ref, preferences.value) ?: return null
        return LookupKey(snapshot.occurrenceId, snapshot.playbackGeneration, ref.id, config)
    }

    private companion object {
        /** Too close to the end to be worth a seek, and close enough that seeking may overshoot. */
        const val SKIP_TAIL_MS = 300L
    }
}

/** Evaluated before every lookup/skip observation, including settings changes while the episode is paused. */
internal fun podcastSponsorBlockConfig(ref: ContentRef, prefs: PreferenceSnapshot?): SponsorBlockConfig? {
    if (ref.kind != ContentKind.EPISODE || prefs == null) return null
    if (!SponsorBlockPlaybackPolicy.allowsLookup(
            ref.id,
            enabled = prefs[SponsorBlockEnabledKey] == true,
            podcastsEnabled = prefs[SponsorBlockPodcastsEnabledKey] == true,
            isPodcast = true,
            isLocal = ref.provider == ContentProvider.LOCAL.id,
            isYouTube = ref.provider == ContentProvider.YOUTUBE.id,
        )) return null
    val defaults = SponsorBlockConfig(enabled = true)
    val categories = prefs[SponsorBlockCategoriesKey] ?: defaults.categories
    if (categories.isEmpty()) return null
    val origin = prefs[SponsorBlockApiUrlKey]?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: defaults.origin
    // URL parsing can throw an IllegalStateException as well as validation's IllegalArgumentException.
    return try { defaults.copy(categories = categories, origin = origin) } catch (_: Exception) { null }
}
