package moe.rukamori.archivetune.desktop

import kotlin.math.roundToLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.desktop.playback.DesktopPlaybackPort
import moe.rukamori.archivetune.shared.data.repository.QueueHandoffEngine
import moe.rukamori.archivetune.shared.domain.playback.PlaybackRequest
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.repository.ContentOccurrence
import moe.rukamori.archivetune.shared.domain.settings.AutomixEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeDurationKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeGaplessKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.transition.CrossfadePolicy
import moe.rukamori.archivetune.shared.domain.transition.TransitionKind

/** Android's crossfade constants, so the desktop blend has the same bounds. */
private const val CROSSFADE_END_GUARD_MS = 150L
private const val MIN_CROSSFADE_DURATION_MS = 500L
private const val MAX_CROSSFADE_SETTING_SECONDS = 10f
private const val DEFAULT_CROSSFADE_SECONDS = 5f

/** Automix's plain-fade tier on Android: the configured crossfade length, or 6 s, within 0.5 to 12 s. */
private const val DEFAULT_SMART_FALLBACK_MS = 6_000L
private const val MAX_SMART_FALLBACK_MS = 12_000L

private const val UNSET_TIME = -1L

/** The stored settings one handoff decision reads, as Android's crossfade configuration collects them. */
internal data class DesktopHandoffSettings(
    val crossfade: Boolean,
    val automix: Boolean,
    val durationSeconds: Float,
    val gapless: Boolean,
)

internal fun PreferenceSnapshot?.desktopHandoffSettings(): DesktopHandoffSettings {
    val automix = this?.get(AutomixEnabledKey) ?: false
    return DesktopHandoffSettings(
        // Crossfade and automix exclude each other; this guards a state written some other way, as Android does.
        crossfade = (this?.get(CrossfadeEnabledKey) ?: false) && !automix,
        automix = automix,
        durationSeconds = this?.get(CrossfadeDurationKey) ?: DEFAULT_CROSSFADE_SECONDS,
        gapless = this?.get(CrossfadeGaplessKey) ?: true,
    )
}

/**
 * The overlap Android's rules give [current] into [next]. Crossfade uses the configured length, clamped to the track.
 * Automix has no on-device analysis on this host yet, so it takes Android's plain-fade tier, the planner's own answer
 * before analysis lands. With the gapless setting on, consecutive songs of one album splice without a blend. Podcast
 * episodes never blend, because their lengths are unreliable. Null lets [current] end on its own.
 */
internal fun desktopHandoffOverlapMs(
    settings: DesktopHandoffSettings,
    current: ContentItem,
    next: ContentItem,
    durationMs: Long,
): Long? {
    if (!settings.crossfade && !settings.automix) return null
    if (current.isEpisode() || next.isEpisode()) return null
    if (settings.gapless && sameAlbum(current, next)) return 0L
    val configuredMs = (settings.durationSeconds.coerceIn(0f, MAX_CROSSFADE_SETTING_SECONDS) * 1000f)
        .roundToLong()
        .coerceAtLeast(0L)
    return if (settings.automix) {
        val fallbackMs = (configuredMs.takeIf { it > 0L } ?: DEFAULT_SMART_FALLBACK_MS)
            .coerceIn(MIN_CROSSFADE_DURATION_MS, MAX_SMART_FALLBACK_MS)
        CrossfadePolicy.effectiveDurationMs(fallbackMs, durationMs, 0L, MIN_CROSSFADE_DURATION_MS, UNSET_TIME)
    } else {
        if (configuredMs <= 0L) return null
        CrossfadePolicy.effectiveDurationMs(configuredMs, durationMs, CROSSFADE_END_GUARD_MS, MIN_CROSSFADE_DURATION_MS, UNSET_TIME)
    }
}

private fun ContentItem.isEpisode(): Boolean = ref.kind == ContentKind.EPISODE || ref.kind == ContentKind.PODCAST

/** Android compares the album id first and falls back to the album title, so this does the same. */
private fun sameAlbum(current: ContentItem, next: ContentItem): Boolean {
    val left = current.albumId ?: current.album?.takeIf { it.isNotBlank() } ?: return false
    val right = next.albumId ?: next.album?.takeIf { it.isNotBlank() } ?: return false
    return left == right
}

/**
 * The desktop crossfade and automix engine: the stored settings decide the overlap and the two-deck port blends it.
 * [hold] is true while a Listen Together room or the sleep timer's end-of-song choice needs each song to end on its
 * own, as Android turns both transitions off in a room.
 */
internal class DesktopCrossfadeHandoff(
    private val port: DesktopPlaybackPort,
    private val settings: StateFlow<PreferenceSnapshot?>,
    private val hold: StateFlow<Boolean>,
    private val analysis: DesktopAutomixAnalysis? = null,
) : QueueHandoffEngine {
    @Volatile private var incomingCue: Pair<String, Long>? = null
    override val configuration: Flow<Any?> = combine(
        settings.map { it.desktopHandoffSettings() }.distinctUntilChanged(),
        hold,
        analysis?.revision ?: flowOf(0L),
    ) { stored, held, revision -> Triple(stored, held, revision) }

    override fun overlapMs(current: ContentOccurrence, next: ContentOccurrence, durationMs: Long): Long? {
        incomingCue = null
        if (hold.value) return null
        val stored = settings.value.desktopHandoffSettings()
        val fallback = desktopHandoffOverlapMs(stored, current.item, next.item, durationMs) ?: return null
        if (!stored.automix || fallback == 0L) return fallback
        val analyzer = analysis ?: return fallback
        analyzer.consider(current.item)
        analyzer.consider(next.item)
        val outgoing = runCatching { analyzer.features(current, requireSelected = true) }.getOrNull() ?: return fallback
        val incoming = runCatching { analyzer.features(next, requireSelected = false) }.getOrNull() ?: return fallback
        // This mixer can execute adaptive equal-power fading/cues. Keep stretch/bass/sweep tiers conservative.
        val plan = moe.rukamori.archivetune.playback.smart.planTransition(
            analysis = outgoing.copy(bpm = 0.0, beatConfidence = 0.0), nextAnalysis = incoming.copy(bpm = 0.0, beatConfidence = 0.0),
            duration = durationMs / 1000.0, fadeSeconds = fallback / 1000.0,
            mode = moe.rukamori.archivetune.playback.smart.CrossfadeMode.SMART)
        if (plan.blocked || plan.fadeSeconds <= 0 || !plan.fadeSeconds.isFinite()) return fallback
        incomingCue = next.occurrenceId to (plan.incomingCueTime.coerceIn(0.0, (incoming.duration - 1.0).coerceAtLeast(0.0)) * 1000).roundToLong()
        return (plan.fadeSeconds * 1000).roundToLong().coerceIn(MIN_CROSSFADE_DURATION_MS, MAX_SMART_FALLBACK_MS)
    }

    override suspend fun arm(request: PlaybackRequest, overlapMs: Long): Boolean {
        val kind = when {
            overlapMs == 0L -> TransitionKind.GAPLESS
            settings.value.desktopHandoffSettings().automix -> TransitionKind.AUTOMIX
            else -> TransitionKind.CROSSFADE
        }
        // The port blocks for one hop to its playback thread, which must never be a snapshot collector's thread.
        val cue = incomingCue?.takeIf { it.first == request.occurrenceId && kind == TransitionKind.AUTOMIX }?.second ?: 0L
        val armed = withContext(Dispatchers.IO) { port.armNext(request, kind, overlapMs, startPositionMs = cue) }
        desktopDiagnostic("transition ${kind.name.lowercase()} into ${request.occurrenceId} over $overlapMs ms: ${if (armed) "armed" else "refused"}")
        return armed
    }

    override fun cancel() = port.cancelNext()
}
