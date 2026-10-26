/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.domain.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IosCapabilitiesTest {
    @Test
    fun sourcePresenceDoesNotAssertAppleExecutionOrPeripheralParity() {
        assertEquals("unverified-on-linux-host", IosCapabilities.verification)
        assertEquals(8, IosCapabilities.adapters.size)
        assertTrue("bit-perfect-output-proof" in IosCapabilities.unavailable)
        assertEquals("common-kuromoji-pack-lifecycle-requires-host-settings",
            IosCapabilities.adapters.getValue("japanese-tokenizer"))
    }
}
