package app.natune.shared.data

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedDataModuleTest {
    @Test
    fun sharedDataModuleDependsOnSharedDomain() {
        assertEquals("shared-domain", SharedDataModule.DOMAIN_MODULE)
    }
}
