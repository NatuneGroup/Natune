package moe.rukamori.archivetune.desktop.playback

import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticAuditTarget
import moe.rukamori.archivetune.shared.domain.dsp.acoustic.AcousticWindow
import moe.rukamori.archivetune.shared.domain.dsp.spectral.SpectralVerdict
import moe.rukamori.archivetune.shared.domain.playback.PlaybackLocator

/**
 * Opt-in spectral evidence for the source at [locator], measured from that source's own decoded PCM at
 * [startPositionMs]. It never analyses engine output, which is resampled to the device rate, and it never
 * touches a deck, the mixer or the playback executor, so it cannot change the audible output or the bit-perfect
 * state.
 *
 * A conclusive verdict is cached under the engine source and start position, so it applies only to that exact
 * source. An inconclusive one is not cached, so a later request retries it. Cancelling the caller stops the
 * native read, and a cancelled check caches nothing.
 */
internal suspend fun DesktopPlaybackPort.assessSpectrum(
    locator: PlaybackLocator,
    startPositionMs: Long = 0L,
): DesktopSpectralEvidence {
    val source = engineSourceOf(locator) ?: return spectral.assess(locator, startPositionMs)
    val key = SpectralCacheKey(source, startPositionMs)
    spectralCache.get(key)?.let { return it }
    val evidence = spectral.assess(locator, startPositionMs)
    if (evidence.assessment.verdict != SpectralVerdict.UNKNOWN) spectralCache.put(key, evidence)
    return evidence
}

/**
 * The acoustic audit's bounded PCM window of the current occurrence's source. Only the loaded request can be
 * audited: the audit decodes the exact source the listener hears, so a track that is not playing has none,
 * and a target that is not the current request is refused instead of auditing the wrong song. The decode
 * happens in the spectral probe, away from the playback executor, exactly like [assessSpectrum].
 */
internal suspend fun DesktopPlaybackPort.acousticWindow(
    target: AcousticAuditTarget,
    checkActive: () -> Unit,
): AcousticWindow {
    checkActive()
    val request = loadedRequest() ?: return AcousticWindow.Refused("no_current_source")
    if (request.mediaId != target.mediaId) return AcousticWindow.Refused("current_source_unavailable")
    return spectral.acousticWindow(request.locator, requestKey = request.occurrenceId)
}
