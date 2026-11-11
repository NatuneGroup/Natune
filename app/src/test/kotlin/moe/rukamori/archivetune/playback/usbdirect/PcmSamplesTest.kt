package moe.rukamori.archivetune.playback.usbdirect

import moe.rukamori.archivetune.playback.PcmSamples
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PcmSamplesTest {
    private fun buffer(size: Int = 32) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    @Test fun preservesIntegerExtremaAndLowBitsInEveryContainer() {
        for (bits in listOf(16, 24, 32)) {
            val shift = 32 - bits
            for (value in listOf(Int.MIN_VALUE, Int.MAX_VALUE shr shift shl shift, 1 shl shift, -1 shl shift)) {
                val pcm = buffer()
                PcmSamples.write(pcm, value, bits)
                pcm.flip()
                assertEquals(value, PcmSamples.read(pcm, bits))
            }
        }
    }
    @Test fun widensPacked24BitTo32BitWithoutChangingSamples() {
        val input = buffer().put(byteArrayOf(1, 2, 3, 0xff.toByte(), 0xff.toByte(), 0x80.toByte()))
        input.flip()
        val output = buffer()
        repeat(2) { PcmSamples.write(output, PcmSamples.read(input, 24), 32) }
        output.flip()
        assertEquals(0x03020100, output.int)
        assertEquals(0x80ffff00.toInt(), output.int)
    }
    @Test fun floatFrom24BitIntegersReturnsTheOriginalSamples() {
        for (value in listOf(-8388608, -12345, -1, 0, 1, 12345, 8388607)) {
            val input = buffer().putFloat(value / 8388608f)
            input.flip()
            assertEquals(value shl 8, PcmSamples.read(input, 32, floating = true, validBits = 24))
        }
    }
    @Test fun fractionalFloatRoundsAtTheDacDepthAndSaturates() {
        val input = buffer().putFloat(1.6f / 8388608f).putFloat(1f).putFloat(-1f).putFloat(Float.NaN)
        input.flip()
        assertEquals(2 shl 8, PcmSamples.read(input, 32, true, 24))
        assertEquals(0x7fffff00, PcmSamples.read(input, 32, true, 24))
        assertEquals(Int.MIN_VALUE, PcmSamples.read(input, 32, true, 24))
        assertEquals(0, PcmSamples.read(input, 32, true, 24))
    }
}
