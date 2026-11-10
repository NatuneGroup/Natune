package moe.rukamori.archivetune.playback.usbdirect

import org.junit.Assert.*
import org.junit.Test

class UsbVolumeTest {
    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
    private class Control(val uac2: Boolean = false, var current: Int = -20 * 256) : UsbControl {
        var mismatch = false
        override fun transfer(input: Boolean, request: Int, value: Int, index: Int, data: ByteArray) {
            if (!input) { current = (data[0].toInt() and 255) or (data[1].toInt() shl 8); return }
            val response = when (request) {
                1, 0x81 -> current + if (mismatch) 256 else 0
                0x82 -> -80 * 256
                0x83 -> 0
                0x84 -> 256
                else -> 1
            }
            data[0] = response.toByte(); data[1] = (response shr 8).toByte()
            if (uac2 && request == 2 && data.size > 2) {
                val values = intArrayOf(-80 * 256, 0, 256)
                for ((i, v) in values.withIndex()) { data[2 + i * 2] = v.toByte(); data[3 + i * 2] = (v shr 8).toByte() }
            }
        }
    }
    @Test fun mapsSystemVolumeToAttenuationWithoutPositiveGain() {
        val snapshot = UsbVolume.Snapshot(-80.0, 0.0)
        assertEquals(-80.0, UsbVolume.decibels(snapshot, 0f), 0.0)
        assertEquals(-30.0, UsbVolume.decibels(snapshot, 0.5f), 0.0)
        assertEquals(0.0, UsbVolume.decibels(snapshot, 1f), 0.0)
        assertEquals(0.0, UsbVolume.decibels(snapshot, 2f), 0.0)
    }
    @Test fun startupOnlyAttenuatesAndReleaseRestoresTheOriginalLevel() {
        for (uac2 in listOf(false, true)) {
            val control = Control(uac2)
            val volume = UsbVolume.Volume.open(control, UsbVolume.Feature(2, 0, uac2, listOf(0)))
            assertEquals(-40.0, volume.snapshot.currentDb, 0.0)
            volume.set(control, -30.2)
            assertEquals(-31.0, volume.snapshot.currentDb, 0.0)
            volume.restore(control)
            assertEquals(-20 * 256, control.current)
        }
    }
    @Test fun startupPreservesAnAlreadyQuieterLevel() {
        val control = Control(current = -70 * 256)
        UsbVolume.Volume.open(control, UsbVolume.Feature(2, 0, false, listOf(0)))
        assertEquals(-70 * 256, control.current)
    }
    @Test(expected = IllegalStateException::class) fun refusesUnverifiedVolumeWrites() {
        val control = Control()
        val volume = UsbVolume.Volume.open(control, UsbVolume.Feature(2, 0, false, listOf(0)))
        control.mismatch = true
        volume.set(control, -30.0)
    }
    @Test(expected = IllegalArgumentException::class) fun refusesNanVolume() {
        UsbVolume.decibels(UsbVolume.Snapshot(-80.0, 0.0), Float.NaN)
    }
    @Test fun featureMustBelongToTheStreamingTerminalPath() {
        val raw = byteArrayOf(9, 4, 0, 0, 0, 1, 1, 0, 0,
            10, 0x24, 6, 2, 1, 1, 2, 0, 0, 0,
            9, 0x24, 3, 3, 1, 3, 0, 2, 0)
        assertEquals(listOf(0), UsbVolume.feature(raw, 0, 1, 2)?.channels)
        assertNull(UsbVolume.feature(raw, 0, 4, 2))
        assertNull(UsbVolume.feature(raw.copyOf(raw.size - 1), 0, 1, 2))
    }
    @Test fun initialAndroidVolumeNeverRaisesTheDacsOriginalLevel() {
        for (uac2 in listOf(false, true)) {
            for (original in listOf(-70, -20, 0)) {
                for (fraction in listOf(0f, 0.25f, 1f)) {
                    val control = Control(uac2, original * 256)
                    val volume = UsbVolume.Volume.open(control, UsbVolume.Feature(2, 0, uac2, listOf(0)))
                    volume.setInitial(control, UsbVolume.decibels(volume.snapshot, fraction))
                    assertEquals(minOf(original.toDouble(), UsbVolume.decibels(volume.snapshot, fraction)),
                        volume.snapshot.currentDb, 0.0)
                    // The sink's initial unity-volume callback must retain the startup ceiling.
                    volume.setInitial(control, UsbVolume.decibels(volume.snapshot, 1f))
                    assertTrue(control.current <= original * 256)
                    volume.set(control, 0.0)
                    assertEquals(0, control.current)
                    volume.restore(control)
                    assertEquals(original * 256, control.current)
                }
            }
        }
    }

}
