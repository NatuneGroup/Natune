package moe.rukamori.archivetune.playback.usbdirect

import org.junit.Assert.*
import org.junit.Test

class UsbDescriptorsTest {
    private fun d(vararg values: Int) = values.map(Int::toByte).toByteArray()
    private val device = d(18, 1, 0, 2, 0, 0, 0, 64, 0x34, 0x12, 0x78, 0x56, 0, 1, 0, 0, 0, 1)
    private val config = d(9, 2, 0, 0, 2, 1, 0, 0x80, 50)
    private val control = d(9, 4, 0, 0, 0, 1, 1, 0, 0)
    private val stream = d(9, 4, 1, 1, 1, 1, 2, 0, 0)
    private val general = d(7, 0x24, 1, 1, 0, 1, 0)
    private val format = d(11, 0x24, 2, 1, 2, 3, 24, 1, 0x80, 0xbb, 0)
    private val endpoint = d(9, 5, 1, 9, 0x00, 0x02, 1, 0, 0)
    private fun raw() = device + config + control + stream + general + format + endpoint

    @Test fun parsesUac1FormatAndDeviceIdentity() {
        val parsed = UsbDescriptors.parse(raw())
        assertEquals(0x1234, parsed.vendor)
        assertEquals(0x5678, parsed.product)
        val plans = UsbDescriptors.plans(raw(), 48000, 2, 24)
        assertEquals(1, plans.size)
        assertEquals(1, plans.single().alternate.configuration)
        assertEquals(3, plans.single().format.subslot)
        assertEquals(24, plans.single().format.bits)
    }
    @Test fun neverResamplesDownmixesOrTruncatesToMatchADac() {
        assertTrue(UsbDescriptors.plans(raw(), 44100, 2, 24).isEmpty())
        assertTrue(UsbDescriptors.plans(raw(), 48000, 1, 24).isEmpty())
        assertTrue(UsbDescriptors.plans(raw(), 48000, 2, 32).isEmpty())
    }
    @Test fun malformedLengthsAndMultipleConfigurationsFallBack() {
        for (bytes in listOf(d(0, 1), d(1), raw().dropLast(1).toByteArray(), raw() + config)) {
            assertTrue(UsbDescriptors.plans(bytes, 48000, 2, 24).isEmpty())
        }
    }
    @Test fun asynchronousDataRequiresAnExplicitFeedbackEndpoint() {
        val async = endpoint.copyOf().apply { this[3] = 5 }
        val prefix = device + config + control + stream + general + format
        assertTrue(UsbDescriptors.plans(prefix + async, 48000, 2, 24).isEmpty())
        val feedback = d(7, 5, 0x81, 0x11, 3, 0, 1)
        assertEquals(0x81, UsbDescriptors.plans(prefix + async + feedback, 48000, 2, 24).single().feedback?.address)
    }
    @Test fun uac2RequiresATerminalClockAndPcmFormat() {
        val ac = control.copyOf().apply { this[7] = 0x20 }
        val input = d(17, 0x24, 2, 1, 1, 1, 0, 10, 2, 3, 0, 0, 0, 0, 0, 0, 0)
        val asInterface = stream.copyOf().apply { this[7] = 0x20 }
        val asGeneral = d(16, 0x24, 1, 1, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)
        val asFormat = d(6, 0x24, 2, 1, 4, 32)
        val prefix = device + config + ac
        val suffix = asInterface + asGeneral + asFormat + endpoint
        assertTrue(UsbDescriptors.plans(prefix + suffix, 96000, 2, 32).isEmpty())
        val plan = UsbDescriptors.plans(prefix + input + suffix, 96000, 2, 32).single()
        assertTrue(plan.alternate.uac2)
        assertEquals(10, plan.alternate.clock)
        assertEquals(4, plan.format.subslot)
    }
    @Test fun malformedHighBandwidthMultiplierIsRejected() {
        val malformed = endpoint.copyOf().apply { this[5] = 0x1a }
        assertTrue(UsbDescriptors.plans(device + config + control + stream + general + format + malformed, 48000, 2, 24).isEmpty())
    }
    @Test fun candidateEncodingMatchesTheNative17IntegerLayout() {
        val plans = UsbDescriptors.plans(raw(), 48000, 2, 24)
        assertArrayEquals(intArrayOf(1, 0, 1, 1, 0, 0, 1, 9, 512, 1, 0, -1, 0, 48000, 2, 3, 24), UsbDirectNative.encode(plans))
    }
    @Test fun continuousUac1RatesRequireEndpointRateControl() {
        val continuous = d(14, 0x24, 2, 1, 2, 3, 24, 0, 0x44, 0xac, 0, 0x80, 0xbb, 0)
        val prefix = device + config + control + stream + general + continuous + endpoint
        assertTrue(UsbDescriptors.plans(prefix, 44100, 2, 24).isEmpty())
        assertEquals(1, UsbDescriptors.plans(prefix + d(7, 0x25, 1, 1, 0, 0, 0), 44100, 2, 24).size)
    }
    @Test fun choosesTheSmallestLosslessContainerAndRejectsReservedPacketSizes() {
        val parsed = UsbDescriptors.parse(raw())
        val alternate = parsed.alternates.single()
        val widened = alternate.copy(alternate = 2, subslot = 4, bits = 32)
        assertEquals(listOf(3, 4), UsbDescriptors.formats(parsed.copy(alternates = listOf(widened, alternate)), 48000, 2, 24)
            .map { it.second.subslot })
        assertNull(UsbDescriptors.plan(alternate.copy(endpoints = listOf(UsbDescriptors.Endpoint(1, 9, 4096, 1))),
            UsbDescriptors.OutputFormat(48000, 2, 24, 3)))
    }
}
