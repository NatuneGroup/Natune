package moe.rukamori.archivetune.playback

/**
 * What the Android session effects may still render now that the software chain owns the graphic equalizer.
 *
 * `EqualizerEnabledKey`, `equalizerBandLevelsMb` and `equalizerOutputGainMb` are read by
 * `UnifiedDspSettingsSource` and rendered by `DspGraph` on the standard route. The session
 * `android.media.audiofx.Equalizer` must therefore stay off and keep its bands untouched: rendering the
 * stored curve there as well would add it twice, once on the shared band centres and once on the device's
 * own band layout, and a restored preset would no longer produce the response it names.
 *
 * Bass boost, the virtualizer and automatic headroom have no software stage, so they keep running on the
 * session effects behind the same stored switches as before. The output gain does not: the chain applies it
 * as the user preamp.
 */
data class FrameworkAudioEffects(
    /** Always false while the software chain renders the bands; the session equalizer is held disabled. */
    val equalizerEnabled: Boolean,
    /** Band levels to write to the session Equalizer; empty while the software chain owns the curve. */
    val bandLevelsMb: List<Int>,
    val bassBoostEnabled: Boolean,
    val bassBoostStrength: Int,
    val virtualizerEnabled: Boolean,
    val virtualizerStrength: Int,
    val loudnessEnabled: Boolean,
    val loudnessTargetGainMb: Int,
)

object FrameworkAudioEffectPolicy {
    /** The one rendering owner of `EqualizerEnabledKey` and `equalizerBandLevelsMb` is the software chain. */
    fun plan(settings: EqSettings): FrameworkAudioEffects =
        FrameworkAudioEffects(
            equalizerEnabled = false,
            bandLevelsMb = emptyList(),
            bassBoostEnabled = settings.enabled && settings.bassBoostEnabled,
            bassBoostStrength = settings.bassBoostStrength.coerceIn(0, MAX_STRENGTH),
            virtualizerEnabled = settings.enabled && settings.virtualizerEnabled,
            virtualizerStrength = settings.virtualizerStrength.coerceIn(0, MAX_STRENGTH),
            // The output gain is the chain's preamp, so the session gain carries automatic headroom only.
            loudnessEnabled = settings.enabled && settings.autoHeadroomEnabled,
            loudnessTargetGainMb = if (settings.autoHeadroomEnabled) headroomMb(settings.bandLevelsMb) else 0,
        )

    /** The attenuation that keeps the highest stored boost inside full scale, as the session effect applied it. */
    private fun headroomMb(bandLevelsMb: List<Int>): Int = -(bandLevelsMb.maxOrNull()?.coerceAtLeast(0) ?: 0)

    /** The platform takes effect strengths and gains in thousandths (0..1000). */
    private const val MAX_STRENGTH = 1000
}
