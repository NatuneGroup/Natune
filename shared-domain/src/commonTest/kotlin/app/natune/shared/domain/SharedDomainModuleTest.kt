package app.natune.shared.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedDomainModuleTest {
    @Test
    fun sharedDomainModuleIsNamedForTheKmpGraph() {
        assertEquals("shared-domain", SharedDomainModule.NAME)
    }
}
