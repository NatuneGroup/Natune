package moe.rukamori.archivetune.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRendererDiagnosticsTest {
    @Test
    fun softwareRasterisationDoesNotOfferTheGlassBlur() {
        // Skiko's two CPU backends; every other enum value is a GPU backend.
        assertFalse(DesktopRendererDiagnostics.blurSupportedFor("SOFTWARE_FAST"))
        assertFalse(DesktopRendererDiagnostics.blurSupportedFor("software_compat"))
    }

    @Test
    fun everyGpuBackendKeepsTheGlassBlur() {
        listOf("DIRECT3D", "OPENGL", "ANGLE", "VULKAN", "METAL", "WEBGL", "UNKNOWN")
            .forEach { api -> assertTrue(DesktopRendererDiagnostics.blurSupportedFor(api), api) }
    }

    @Test
    fun anUnreportedRendererKeepsThePreProbeDefault() {
        assertTrue(DesktopRendererDiagnostics.blurSupportedFor(null))
    }

    @Test
    fun skikoRendererLinesGetTheirOwnLogTag() {
        // A fallback line must be findable without knowing that it arrived on standard error.
        assertEquals(DesktopDiagnosticLog.SKIKO_TAG, diagnosticLineTag("[SKIKO] warn: Fallback to next API", "stderr"))
        assertEquals("stderr", diagnosticLineTag("Natune desktop: integration unavailable", "stderr"))
        assertEquals("stdout", diagnosticLineTag("", "stdout"))
    }

    @Test
    fun theReportNamesTheRequestedAndActiveRenderer() {
        val description = DesktopRendererDiagnostics.describe()

        assertTrue(description.startsWith("requested="))
        assertTrue(description.contains("active="))
        assertTrue(description.contains("blur="))
    }
}
