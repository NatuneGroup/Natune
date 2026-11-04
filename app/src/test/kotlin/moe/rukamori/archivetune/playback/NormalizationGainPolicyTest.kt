package moe.rukamori.archivetune.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NormalizationGainPolicyTest {
    @Test
    fun `an enabled normalization setting applies while processing is on`() {
        assertTrue(NormalizationGainPolicy.applies(requested = true, bitPerfectSinkActive = false, processingEnabled = true))
    }

    @Test
    fun `the processing switch keeps the normalization gain out of the volume path`() {
        assertFalse(NormalizationGainPolicy.applies(requested = true, bitPerfectSinkActive = false, processingEnabled = false))
    }

    @Test
    fun `a disabled normalization setting never applies`() {
        assertFalse(NormalizationGainPolicy.applies(requested = false, bitPerfectSinkActive = false, processingEnabled = true))
    }

    @Test
    fun `a bit-perfect sink still sits out`() {
        assertFalse(NormalizationGainPolicy.applies(requested = true, bitPerfectSinkActive = true, processingEnabled = true))
    }
}
