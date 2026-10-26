package moe.rukamori.archivetune.shared.domain.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosCapabilitiesTest {
    @Test
    fun sourcePresenceDoesNotAssertAppleExecutionOrPeripheralParity() {
        assertEquals("unverified-on-linux-host", IosCapabilities.verification)
        assertEquals(9, IosCapabilities.adapters.size)
        assertTrue("bit-perfect-output-proof" in IosCapabilities.unavailable)
        assertEquals("common-kuromoji-pack-lifecycle-requires-host-settings",
            IosCapabilities.adapters.getValue("japanese-tokenizer"))
    }
}
