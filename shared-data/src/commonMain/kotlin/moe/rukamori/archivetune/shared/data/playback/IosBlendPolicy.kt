package moe.rukamori.archivetune.shared.data.playback

import kotlin.math.roundToLong
import moe.rukamori.archivetune.shared.domain.repository.ContentItem
import moe.rukamori.archivetune.shared.domain.repository.ContentKind
import moe.rukamori.archivetune.shared.domain.settings.AutomixEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeDurationKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeEnabledKey
import moe.rukamori.archivetune.shared.domain.settings.CrossfadeGaplessKey
import moe.rukamori.archivetune.shared.domain.settings.PreferenceSnapshot
import moe.rukamori.archivetune.shared.domain.transition.CrossfadePolicy
import moe.rukamori.archivetune.shared.domain.transition.EqualPowerEnvelope
import moe.rukamori.archivetune.shared.domain.transition.TransitionGains

internal data class IosBlendSettings(
    val crossfade: Boolean,
    val automix: Boolean,
    val seconds: Float,
    val gapless: Boolean,
)

internal fun PreferenceSnapshot?.iosBlendSettings(): IosBlendSettings {
    val automix = this?.get(AutomixEnabledKey) ?: false
    return IosBlendSettings(
        crossfade = (this?.get(CrossfadeEnabledKey) ?: false) && !automix,
        automix = automix,
        seconds = this?.get(CrossfadeDurationKey)?.takeIf { it.isFinite() } ?: 5f,
        gapless = this?.get(CrossfadeGaplessKey) ?: true,
    )
}

/** The Android/desktop bounds, including automix's analysis-free plain-fade tier. */
internal fun iosBlendOverlapMs(settings: IosBlendSettings, current: ContentItem, next: ContentItem, durationMs: Long): Long? {
    if (!settings.crossfade && !settings.automix) return null
    if (current.ref.kind in EPISODE_KINDS || next.ref.kind in EPISODE_KINDS) return null
    val leftAlbum = current.albumId ?: current.album?.takeIf { it.isNotBlank() }
    val rightAlbum = next.albumId ?: next.album?.takeIf { it.isNotBlank() }
    if (settings.gapless && leftAlbum != null && leftAlbum == rightAlbum) return 0L
    val seconds = settings.seconds.takeIf { it.isFinite() } ?: 5f
    val configuredMs = (seconds.coerceIn(0f, 10f) * 1_000f).roundToLong()
    val requestedMs = if (settings.automix) {
        (configuredMs.takeIf { it > 0L } ?: 6_000L).coerceIn(500L, 12_000L)
    } else {
        configuredMs.takeIf { it > 0L } ?: return null
    }
    return CrossfadePolicy.effectiveDurationMs(
        requestedMs, durationMs, if (settings.automix) 0L else 150L, 500L, -1L,
    )
}

/** Media progress, not elapsed wall time, owns the envelope. A paused or starved incoming node cannot fade out music. */
internal class IosBlendProgress(private val durationMs: Long, outgoingPositionMs: Long) {
    private var previousOutgoingMs = outgoingPositionMs
    private var previousIncomingMs = 0L
    var elapsedMs: Long = 0L
        private set

    fun advance(outgoingPositionMs: Long, incomingPositionMs: Long, incomingAdvancing: Boolean): TransitionGains {
        elapsedMs = CrossfadePolicy.advanceFadeElapsedMs(
            elapsedMs, previousOutgoingMs, outgoingPositionMs, durationMs,
            incomingAdvancing && incomingPositionMs > previousIncomingMs,
        )
        previousOutgoingMs = outgoingPositionMs
        previousIncomingMs = incomingPositionMs
        return gains()
    }

    fun gains(): TransitionGains {
        val progress = if (durationMs <= 0L) 1.0 else elapsedMs.toDouble() / durationMs
        return TransitionGains(EqualPowerEnvelope.outgoingGain(progress), EqualPowerEnvelope.incomingGain(progress))
    }

    val complete: Boolean get() = durationMs <= 0L || elapsedMs >= durationMs
}

/** One scheduled PCM span maps an observed player sample time back to the decoder's real media positions. */
internal data class IosPcmSpan(val startFrame: Long, val endFrame: Long, val startUs: Long, val endUs: Long) {
    fun positionUs(frame: Long): Long = when {
        frame <= startFrame -> startUs
        frame >= endFrame -> endUs
        else -> startUs + ((endUs - startUs).toDouble() * (frame - startFrame) / (endFrame - startFrame)).toLong()
    }
}

private val EPISODE_KINDS = setOf(ContentKind.EPISODE, ContentKind.PODCAST)
