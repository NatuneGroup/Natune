/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 * Portions © vossgraves — github.com/vossgraves
 */
package moe.rukamori.archivetune.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedDataModuleTest {
    @Test
    fun sharedDataModuleDependsOnSharedDomain() {
        assertEquals("shared-domain", SharedDataModule.DOMAIN_MODULE)
    }
}
