package moe.rukamori.archivetune.playback

import java.nio.ByteBuffer
import kotlin.math.roundToLong

/** Signed integer PCM uses a left-justified 32-bit value between containers. */
internal object PcmSamples {
    fun read(input: ByteBuffer, bits: Int, floating: Boolean = false, validBits: Int = 32): Int {
        if (floating) {
            val value = input.float
            if (value.isNaN()) return 0
            val shift = 32 - validBits
            val scale = (1L shl (validBits - 1)).toDouble()
            val rounded = (value.toDouble() * scale).roundToLong()
                .coerceIn(-(1L shl (validBits - 1)), (1L shl (validBits - 1)) - 1)
            return (rounded shl shift).toInt()
        }
        return when (bits) {
            16 -> input.short.toInt() shl 16
            24 -> {
                val low = input.get().toInt() and 255
                val mid = input.get().toInt() and 255
                val high = input.get().toInt()
                ((high shl 16) or (mid shl 8) or low) shl 8
            }
            32 -> input.int
            else -> error("Unsupported PCM depth $bits")
        }
    }

    fun write(output: ByteBuffer, sample: Int, bits: Int, floating: Boolean = false) {
        if (floating) { output.putFloat((sample.toDouble() / 2147483648.0).toFloat()); return }
        when (bits) {
            16 -> output.putShort((sample shr 16).toShort())
            24 -> {
                val value = sample shr 8
                output.put(value.toByte()).put((value shr 8).toByte()).put((value shr 16).toByte())
            }
            32 -> output.putInt(sample)
            else -> error("Unsupported PCM depth $bits")
        }
    }
}
