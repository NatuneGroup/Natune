package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C

/**
 * One spelling for a PCM encoding in diagnostics lines. The exclusive AAudio stream reports what it was
 * opened with next to what the sink asked for, so both sides of that comparison read the same way.
 */
internal fun pcmEncodingName(encoding: Int): String = when (encoding) {
    C.ENCODING_PCM_16BIT -> "pcm16"
    C.ENCODING_PCM_FLOAT -> "float"
    C.ENCODING_PCM_24BIT -> "pcm24"
    C.ENCODING_PCM_32BIT -> "pcm32"
    else -> "encoding[$encoding]"
}
