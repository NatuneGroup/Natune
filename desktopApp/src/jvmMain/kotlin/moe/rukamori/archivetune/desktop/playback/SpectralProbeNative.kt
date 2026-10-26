package moe.rukamori.archivetune.desktop.playback

/**
 * The JNI entry points of the source-rate PCM probe in `libarchivetune_engine`, one for one with
 * `archivetune_probe_jni.c`.
 *
 * The C side binds these by name and signature, so a rename or a parameter change here needs the same change in
 * that file. Callers use [NativeSpectralProbe], which owns the handle and decodes the status; nothing else should
 * call these directly.
 */
internal object SpectralProbeNative {
    @JvmStatic external fun create(): Long

    @JvmStatic external fun destroy(handle: Long)

    /** Makes the read in progress on [handle] end as cancelled. Safe to call from any thread. */
    @JvmStatic external fun cancel(handle: Long)

    /**
     * Decodes up to [maxFrames] frames of the first audio stream of [source], starting at or before [startUs], into
     * [out] as interleaved float samples at the stream's own channel count. Fills [info] with six values: status,
     * frames, channels, PCM sample rate, advertised sample rate and the end-of-source flag. Returns null on success,
     * or the failure message.
     */
    @JvmStatic external fun read(
        handle: Long,
        source: String,
        startUs: Long,
        maxFrames: Int,
        out: FloatArray,
        info: LongArray,
    ): String?

    /** The FFmpeg codec name of the last read on [handle], empty when that read opened no decoder. */
    @JvmStatic external fun codecName(handle: Long): String
}
