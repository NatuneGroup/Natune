package moe.rukamori.archivetune.shared.data.platform.ios

import cnames.structs.at_audio_output
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import moe.rukamori.archivetune.nativeaudio.at_audio_create
import moe.rukamori.archivetune.nativeaudio.at_audio_create_plain
import moe.rukamori.archivetune.shared.data.playback.createAudioOutputWithFallback

private const val OUTPUT_CREATION_FAILED = "iOS audio output rejected the canonical PCM format"

/** Both constructors catch Apple exceptions and clean up partial graphs. No URL, account, or lease reaches this retry.
 * No AVPlayer playback adapter exists here, so a refused plain graph reports OUTPUT_FAILED to the queue's policy. */
@OptIn(ExperimentalForeignApi::class)
internal fun createIosAudioOutput(onFailure: (String?) -> Unit = {}): CPointer<at_audio_output>? = memScoped {
    val error = allocArray<ByteVar>(256)
    val output = createAudioOutputWithFallback(
        primary = { at_audio_create(error, 256uL) },
        plain = { at_audio_create_plain(error, 256uL) },
    )
    // Do not decode arbitrary native error text: the stable wording is safe even when the core refused creation.
    onFailure(if (output == null) OUTPUT_CREATION_FAILED else null)
    output
}
