package moe.rukamori.archivetune.desktop.playback

/**
 * The JNI entry points of the two-deck mixer in `libarchivetune_engine`, one for one with
 * `archivetune_mixer_jni.c`.
 *
 * The C side binds these by name and signature, so a rename or a parameter change here needs the
 * same change in that file. Callers use [NativeTwoDeckMixer], which owns the handle and decodes the
 * snapshot; nothing else should call these directly.
 */
internal object TwoDeckMixerNative {
    @JvmStatic external fun create(backend: Int, sampleRate: Int, ringMs: Int, prebufferMs: Int, exclusive: Boolean, deviceId: String): Long
    @JvmStatic external fun setOutput(handle: Long, exclusive: Boolean, deviceId: String)
    @JvmStatic external fun prepareTrack(handle: Long)
    @JvmStatic external fun outputEnabled(handle: Long, enabled: Boolean)
    @JvmStatic external fun outputTick(handle: Long)
    @JvmStatic external fun outputSnapshot(handle: Long, values: LongArray, texts: Array<String?>)
    @JvmStatic external fun outputDevices(): Array<String>

    @JvmStatic external fun destroy(handle: Long)

    /** Returns the borrowed engine handle of deck 0 or 1, or 0 for any other index. */
    @JvmStatic external fun deck(handle: Long, deck: Int): Long

    /** Returns 0 when the handoff was scheduled, or 1 when the mixer refused it. */
    @JvmStatic external fun arm(handle: Long, incoming: Int, startFrame: Long, overlapFrames: Long): Int

    @JvmStatic external fun cancel(handle: Long)

    /** Returns true when an armed handoff became the active deck at once. */
    @JvmStatic external fun promote(handle: Long): Boolean

    @JvmStatic external fun setDeckGain(handle: Long, deck: Int, gain: Float)

    @JvmStatic external fun setBypass(handle: Long, bypass: Boolean)

    /** Packed biquad sections, five doubles each: b0, b1, b2, a1, a2. Returns 0 when replaced, 1 when refused. */
    @JvmStatic external fun setEqualizer(handle: Long, coefficients: DoubleArray): Int

    /**
     * Fills [out] with twelve values: timeline frames, commit sequence, armed start frame, armed end
     * frame, active deck, incoming deck, armed flag, bypass flag, equalizer section count, active deck
     * position in microseconds, active deck duration in microseconds, and the active deck speed bits.
     */
    @JvmStatic external fun snapshot(handle: Long, out: LongArray)
}
