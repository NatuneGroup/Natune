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
 * Seeks past SponsorBlock segments in songs and podcast episodes that play from YouTube, the shared
 * counterpart of the Android `SponsorBlockPlaybackController`. Songs need only the main opt-in;
 * episodes additionally need the podcast opt-in, exactly like the Android host. The driver reads
 * only the snapshots the player already publishes, so it adds no ticker. Segment lookups are
 * occurrence- and settings-bound child jobs, so switching tracks/accounts or disabling an opt-in
 * discards an in-flight response rather than continuing to skip cached segments.
 */
class SponsorBlockDriver(
    private val scope: CoroutineScope,
    private val player: MusicPlayerController,
    private val queue: StateFlow<QueueState>,
    private val preferences: StateFlow<PreferenceSnapshot?>,
    private val repository: SponsorBlockSegmentSource,
    private val report: (String) -> Unit = {},
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
        val segment = skippableSegment(current.segments, position, snapshot.durationMs) ?: return
        if (!skippedEnds.add(segment.endMs)) return
        report("sponsorblock: skipping ${segment.category}: ${position}ms -> ${segment.endMs}ms")
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
        val config = sponsorBlockConfig(ref, preferences.value) ?: return null
        return LookupKey(snapshot.occurrenceId, snapshot.playbackGeneration, ref.id, config)
    }
}

/**
 * The segment a seek should leave from this position: the first one it is inside, minus a tail margin
 * where a seek is not worth it. A segment that runs to the end of the track is left to play out, because
 * landing past the end would finish the track early. A null duration cannot prove the tail, so it passes.
 */
internal fun skippableSegment(segments: List<SkipSegment>, positionMs: Long, durationMs: Long?): SkipSegment? {
    val segment = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - SKIP_TAIL_MS }
        ?: return null
    if (durationMs != null && segment.endMs >= durationMs - SKIP_TAIL_MS) return null
    return segment
}

/** Too close to the end to be worth a seek, and close enough that seeking may overshoot. */
private const val SKIP_TAIL_MS = 300L

/**
 * Evaluated before every lookup/skip observation, including settings changes while the track is paused.
 * Songs and episodes are the only playable kinds; episodes additionally need the podcast opt-in.
 */
internal fun sponsorBlockConfig(ref: ContentRef, prefs: PreferenceSnapshot?): SponsorBlockConfig? {
    val isPodcast = when (ref.kind) {
        ContentKind.SONG -> false
        ContentKind.EPISODE -> true
        else -> return null
    }
    if (prefs == null) return null
    if (!SponsorBlockPlaybackPolicy.allowsLookup(
            ref.id,
            enabled = prefs[SponsorBlockEnabledKey] == true,
            podcastsEnabled = prefs[SponsorBlockPodcastsEnabledKey] == true,
            isPodcast = isPodcast,
            isLocal = ref.provider == ContentProvider.LOCAL.id,
            isYouTube = ref.provider == ContentProvider.YOUTUBE.id,
        )) return null
    val defaults = SponsorBlockConfig(enabled = true)
    val categories = prefs[SponsorBlockCategoriesKey] ?: defaults.categories
    if (categories.isEmpty()) return null
    val origin = prefs[SponsorBlockApiUrlKey]?.trim()?.takeIf(String::isNotBlank) ?: defaults.origin
    // URL parsing can throw an IllegalStateException as well as validation's IllegalArgumentException.
    return try {
        // Validate before removing the root slash: malformed values must not become a default origin.
        val config = defaults.copy(categories = categories, origin = origin)
        if (origin.endsWith('/')) config.copy(origin = origin.removeSuffix("/")) else config
    } catch (_: Exception) { null }
}
