package moe.rukamori.archivetune.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedDataModuleTest {
    @Test
    fun sharedDataModuleDependsOnSharedDomain() {
        assertEquals("shared-domain", SharedDataModule.DOMAIN_MODULE)
    }
}
