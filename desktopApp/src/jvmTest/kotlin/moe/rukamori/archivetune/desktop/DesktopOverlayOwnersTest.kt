package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopOverlayOwnersTest {
    @Test
    fun nothingClosesTheOwnersBeforeTheRootDoes() {
        val closed = mutableListOf<String>()
        val owners = DesktopOverlayOwners()
        owners.own({ closed += "podcasts" }, { closed += "topMixes" })
        owners.own({ closed += "canvas" })
        assertEquals(emptyList<String>(), closed)
        owners.close()
        assertEquals(listOf("podcasts", "topMixes", "canvas"), closed)
    }

    @Test
    fun theRootCloseRunsOnceAndALateOwnerClosesAtOnce() {
        val closed = mutableListOf<String>()
        val owners = DesktopOverlayOwners()
        owners.own({ closed += "first" })
        owners.close()
        owners.close()
        assertEquals(listOf("first"), closed)
        owners.own({ closed += "late" })
        assertEquals(listOf("first", "late"), closed)
    }
}
