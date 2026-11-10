package moe.rukamori.archivetune.playback.usbdirect

import java.nio.ByteBuffer

internal object UsbDirectNative {
    val available = runCatching { System.loadLibrary("archivetune_usb") }.isSuccess

    external fun nativeOpen(fd: Int, candidates: IntArray, result: IntArray): Long
    external fun nativeWrite(handle: Long, buffer: ByteBuffer, offset: Int, count: Int): Int
    external fun nativeSetPlaying(handle: Long, playing: Boolean): Int
    external fun nativeFlush(handle: Long): Int
    external fun nativePlayedFrames(handle: Long): Long
    external fun nativeControl(handle: Long, input: Boolean, request: Int, value: Int, index: Int, data: ByteArray): Int
    external fun nativeClose(handle: Long)

    fun encode(plans: List<UsbDescriptors.Plan>): IntArray = plans.flatMap { plan ->
        val alternate = plan.alternate
        val format = plan.format
        listOf(
            alternate.configuration, alternate.control, alternate.interfaceNumber, alternate.alternate,
            if (alternate.uac2) 1 else 0, alternate.clock,
            plan.data.address, plan.data.attributes, plan.data.maxPacket, plan.data.interval,
            if (plan.data.rateControl) 1 else 0, plan.feedback?.address ?: -1, plan.feedback?.maxPacket ?: 0,
            format.sampleRate, format.channels, format.subslot, format.bits,
        )
    }.toIntArray()
}
