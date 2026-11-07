package moe.rukamori.archivetune.desktop.integration.linux

import java.awt.Dimension
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The window-size reconciliation: AWT's peer can keep the size the app asked for while the compositor shows another,
 * and the size on screen is the one the UI has to be laid out for.
 */
class LinuxWindowGeometryTest {
    @Test
    fun anAgreeingPairNeedsNoResync() {
        assertNull(reconciledWindowSize(Dimension(955, 1030), Dimension(955, 1030)))
    }

    @Test
    fun theServerSizeWinsWhenTheTwoDisagree() {
        // The real-PC case: the app asked for its 1080 x 720 default, the compositor had the window at 955 x 1030.
        assertEquals(Dimension(955, 1030), reconciledWindowSize(Dimension(1080, 720), Dimension(955, 1030)))
        // A single pixel of divergence is the same defect: the uncovered strip is exactly that tall.
        assertEquals(Dimension(955, 1030), reconciledWindowSize(Dimension(955, 1029), Dimension(955, 1030)))
        // The result never aliases the caller's dimension, so a later mutation cannot rewrite the report.
        val server = Dimension(1400, 1030)
        val target = reconciledWindowSize(Dimension(955, 1030), server)
        assertEquals(server, target)
        server.width = 1
        assertEquals(1400, target?.width)
    }
}
